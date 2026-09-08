## 1. CSV 导出契约测试

- [x] 1.1 先为 CSV UTF-8 BOM、固定列顺序、逗号/引号/换行转义、空数据标题行和敏感字段排除编写单元测试，并确认新用例先失败。
- [x] 1.2 先为会员、充值交易和游玩导出端点编写集成测试，覆盖完整数据、稳定排序、积分/排名投影、历史金额和界面筛选无关性。
- [x] 1.3 增加导出只读事务并发测试，验证单个文件内部是一致快照且导出不修改 `database_state.revision`。

## 2. 导出后端与桌面桥接

- [x] 2.1 实现三个窄导出查询与流式 CSV 响应，运行导出 service/controller 测试验证空库和中文内容。
- [x] 2.2 为 member-admin Electron 新增数据集白名单、操作账号上下文、保存对话框和同目录临时文件原子落盘逻辑，并先补充取消、接口失败、写入失败和成功测试。
- [x] 2.3 在会员页面和记录页面加入对应导出入口、非数据库备份说明及状态提示，运行 UI 测试验证每个入口使用正确数据集。
- [x] 2.4 复用原生对话框焦点恢复机制并增加输入框回归测试，验证取消或完成导出后页面输入仍可获得光标。

## 3. 逐关积分策略测试

- [x] 3.1 先为 `level-clear-points-v1` 编写策略单元测试，覆盖自然成功、自然失败、非自然终止、并列玩家各自请求、明细合计和整数溢出。
- [x] 3.2 先扩展 result API 集成测试，覆盖无效 `scoringInput` 不关闭 play、有效结果原子保存、重复请求不二次加分及显式无效不回退。
- [x] 3.3 保留并扩展旧客户端测试，验证缺少 `scoringInput` 时仍使用 `raw-score-v1`，旧 `pointsAwarded` 继续不具权威性。

## 4. 逐关积分策略实现

- [x] 4.1 增加 `scoringInput` DTO、版本/范围/level index 唯一/明细合计校验，并运行参数化校验测试。
- [x] 4.2 扩展 `GamePointsPolicy` 选择 `level-clear-points-v1` 或兼容 `raw-score-v1`，确保终止原因优先决定非自然中止为 0。
- [x] 4.3 在单 play 原子结算中分别保存 `raw_score`、`points_awarded`、`scoring_policy` 和规范化审计依据，运行幂等与并发结算测试。
- [x] 4.4 更新 API client 类型、会员/记录/排行榜投影测试，验证排行榜仅累计平台最终 `points_awarded`。

## 5. 综合验证

- [x] 5.1 运行 `pnpm test:server`、`pnpm test:client`、`pnpm typecheck` 和 `pnpm build:member-admin`，修复全部回归。
- [x] 5.2 扩展核心跨端验收场景，覆盖多人 Simple 全员获奖、Rank 单冠军/并列冠军、自然失败保留已通关关卡积分和手动结束为 0。
- [ ] 5.3 在开发桌面版手动导出三类 CSV 到自选目录，用中文表格软件验证编码、列与焦点恢复，并确认 SQLite 备份/导入页面不受影响。
- [x] 5.4 运行 `openspec validate enhance-game-client-management-and-member-data-export --strict` 并确认该仓库 Change 有效。
