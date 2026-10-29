## Context

会员平台的会员、充值和游玩记录都来自 SQLite，会员管理端通过 Electron main 代理 HTTP 请求。现有页面只展示查询结果，没有文件导出 IPC。桌面端已经有原生对话框焦点恢复封装和操作账号上下文。

游玩结算当前把所有 `NATURAL_` 结果交给 `raw-score-v1`，以 `max(0, rawScore)` 保存 `points_awarded`，并忽略客户端提交的旧 `pointsAwarded`。新规则需要接受可验证的结构化关卡依据，但仍由平台决定最终积分。

## Goals / Non-Goals

**Goals:**

- 提供三类完整、稳定、可在中文 Windows 打开的 CSV 数据导出。
- 让 renderer 不获得任意文件写入权限，并保证取消/失败不产生伪成功。
- 扩展版本化积分策略，同时保持 `rawScore` 的独立审计意义和旧客户端兼容。
- 维持 play 结算的一次性、事务性和排行榜实时一致性。

**Non-Goals:**

- CSV 不作为数据库恢复输入，不替代 SQLite 异盘备份。
- 不导出密码摘要、授权文件、内部备份状态或日志。
- 不新增第三方报表、Excel 依赖、定时导出或云端同步。
- 不让游戏端直接指定最终 `points_awarded`。

## Decisions

### 1. 后端生成 CSV 快照，Electron 只负责选择路径和原子落盘

新增三个明确的数据集端点，例如 `/api/exports/members.csv`、`/api/exports/wristband-charges.csv`、`/api/exports/game-plays.csv`。每个端点用一次只读事务查询完整数据集，固定 SQL 排序和列定义，再输出带 UTF-8 BOM 的 CSV。CSV writer 只实现必要的逗号、引号、CR/LF 转义，不引入大型报表库。

会员导出查询同时投影完成积分总额与排名；充值导出使用记录中已保存的 `unit_price_cents`/`amount_cents`，不按当前配置重新计算历史金额；游玩导出保留 raw/awarded/policy。

renderer 调用窄 IPC 并指定数据集 key。main 先显示 `showSaveDialog`，取消即结束；选择成功后请求本机后端 CSV，写入目标同目录临时文件，再 rename/replace 到目标，以避免半文件。原生对话框和完成提示沿用焦点恢复封装。

备选方案是 renderer 把当前表格 DOM 转成 CSV；它会受筛选和分页影响且扩大文件权限，因此不采用。另一个方案是一次 ZIP 导出三个文件；用户要求三类可分别导出，单文件 Save As 更直观。

### 2. 导出权限沿用“已登录操作账号”模型

导出 IPC 只在会员管理桌面窗口可用，并附带当前 `X-Operator-Id`。后端要求该账号仍存在且启用，但不额外限制为 `FACTORY_ADMIN`；导出是日常运营能力，不是数据库覆盖操作。现有前端权限模型不升级为 token/session 系统。

### 3. 新积分策略接受依据，不接受最终积分指令

结果请求增加可选 `scoringInput`：`version`、`awardEligible`、`totalPoints` 和逐关明细。`GamePointsPolicy` 根据字段是否存在选择策略：

- 不存在：沿用 `raw-score-v1`。
- 存在且版本为 `level-clear-points-v1`：验证明细后，由平台根据终止原因和 `awardEligible` 计算最终积分。
- 显式存在但无效：返回 `400`，不静默回退。

逐关明细校验 level index 唯一且非负，`rewardPoints`/`awardedPoints` 为限定范围整数，`awardedPoints` 不大于 `rewardPoints`，`totalPoints` 等于 awarded 合计并执行溢出检查。平台不接受或读取旧 `pointsAwarded` 作为权威值。

自然终止且 `awardEligible=true` 时保存合计；非自然终止强制为 0。策略版本写入 `scoring_policy`，规范化依据与其他结果诊断一起写入 `result_json`，从而能解释 raw score 与会员积分为何不同。

### 4. 继续使用单 play 原子结算

不新增批量结果 API。游戏后端仍逐 participant 调用现有 result endpoint；`WHERE id=? AND status='RUNNING'` 保证第一次有效提交获胜，重试返回已保存结果。验证发生在 UPDATE 之前，无效输入不关闭 play，允许以同一 play ID 修正重试。

排行榜和会员积分查询继续 `SUM(points_awarded)`，不需要额外积分流水表即可满足当前范围。

## Risks / Trade-offs

- [大数据集一次生成 CSV 会占用内存] → 使用流式 JDBC/CSV response 或有界缓冲；当前门店规模不引入异步任务系统。
- [保存对话框后查询失败会留下用户以为存在的文件] → 查询成功后才创建临时文件，失败明确提示且不触碰目标。
- [导出期间并发写入] → 每个数据集使用同一只读事务快照；三个独立导出不承诺跨文件同一时点。
- [游戏端可伪造关卡依据] → 网络仍是受控局域网，平台执行结构/终止原因校验并保留审计明细；不引入密钥签名，与当前信任游戏端 raw score 的边界一致。
- [新旧版本混合时积分规则不同] → `scoring_policy` 明确记录实际策略；部署顺序先平台后游戏后端。

## Migration Plan

1. 先发布支持可选 `scoringInput` 的平台；现有请求继续使用 `raw-score-v1`。
2. 发布会员管理端导出 UI/IPC；旧平台无端点时显示明确不可用错误。
3. 最后发布游戏后端逐关积分上报，使新 play 使用 `level-clear-points-v1`。
4. 回滚游戏后端后，新平台自动回到旧请求策略；数据库无需 schema 回滚。

