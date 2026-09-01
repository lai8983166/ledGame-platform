## Context

会员平台当前用 `started_at + duration_minutes` 推算手环的连续有效期，并在游戏配置阶段首次刷卡时把绑定改为 `ACTIVE`。游戏结果接口只保存积分与终止原因，没有本局实际运行时长，因此平台无法区分配置、启动、运行和结算时间。游戏平台 SQLite 是手环余额和会员数据的唯一权威来源；游戏后端负责观察 GameEngine 生命周期。

## Goals / Non-Goals

**Goals:**

- 将余额改为可持久化、可幂等扣减的整数秒数。
- 让一次 play 结算同时完成用量留痕、余额扣减和绑定状态恢复。
- 兼容多人 participant play、网络重试和已有数据库迁移。
- 保持会员管理端、自助查询和自动化验收使用同一权威余额口径。

**Non-Goals:**

- 平台不根据前端页面倒计时、房间在线时长或结果到达时间推算用量。
- 不改变充值价格、充值记录、积分规则或收入统计口径。
- 不提供跨进程的亚秒级实时余额广播；运行中的页面只展示“游戏中”和最近一次权威余额，最终扣减在结算后可见。

## Decisions

### 1. 余额使用 `remaining_seconds`，购买记录继续保留分钟

在当前 open binding 上增加非负整数 `remaining_seconds`，充值 `duration_minutes` 同时初始化为 `duration_minutes * 60`。`duration_minutes` 保留为本次购买与审计字段，所有准入判断改用 `remaining_seconds`。

选择整数秒是因为现有产品以分钟售卖、界面以秒展示，精度足够且避免浮点累计误差。没有继续使用 `started_at/expires_at`，因为墙钟时间无法表达只在 `RUNNING` 中消耗。

### 2. play 保存实际用量，结算事务负责一次扣减

`game_play` 增加 `running_duration_millis` 与 `consumed_seconds`。游戏后端提交非负 `runningDurationMillis`；平台先校验终态请求，再将全局累计毫秒一次性向上取整为秒，并把结果限制在该 binding 的当前可用余额以内。

play 终态写入、会员积分写入、`remaining_seconds` 扣减和绑定状态转换在一个 SQLite 事务中完成。已结算 `playId` 直接返回已有结果，不再次扣时或加分。向上取整在整局结束时只做一次，避免每个关卡分别取整造成额外损耗。

### 3. `ACTIVE` 表示正在被一局占用，不表示墙钟计费

准入扫描保持 binding 为 `READY`。confirm 创建 play 时把 binding 改为 `ACTIVE`，用于阻止同一手环被并发使用；这一步仍不扣时。结算后余额大于零则恢复 `READY`，为零则转为 `EXPIRED`。

这比引入另一个 `RESERVED` 状态改动更小，同时清除了旧代码中 `ACTIVE` 等同于连续计时的含义。

### 4. 结算信任游戏后端的生命周期累计，平台只负责边界保护

平台看不到 GameEngine 的 `RUNNING/SETTLING` 转换，因此不使用平台墙钟计算本局用量。结果接口接收游戏后端已经冻结的 `runningDurationMillis`；平台拒绝负数、溢出和与既有终态不一致的请求，并以余额上限防止扣成负数。

没有增加每秒跨机扣款接口。这样能降低局域网波动和 SQLite 高频写入对核心流程的影响，且现有 outbox 已能可靠补交最终结果。

### 5. 多人沿用每位玩家一个 platform play

同一游戏 session 为每位手环创建独立 play，游戏后端在结束时提交相同的 `runningDurationMillis`。每个 play 以自身 `playId` 幂等结算，平台不要求所有玩家结果必须在同一个 HTTP 事务到达；重试只影响尚未结算的 participant。

### 6. 旧数据库迁移采用保守余额换算

- `READY`：`remaining_seconds = duration_minutes * 60`。
- 旧 `ACTIVE`：用旧 `started_at`、`duration_minutes` 和迁移时平台时间计算尚未流逝的秒数，保存为 `remaining_seconds`；大于零归一为 `READY`，否则归一为 `EXPIRED`。
- `CHARGED`：保存已购买的完整秒数，等待绑定。
- `EXPIRED/EMPTY`：余额为零。

升级期间必须先停止旧版游戏端，避免无法判断旧 `ACTIVE` 是否仍对应正在运行的游戏。该迁移不会返还旧连续窗口中已经流逝的时间。

## Risks / Trade-offs

- [运行中管理端看到的是最近一次已提交余额，不是逐秒数据库扣减] → 明确显示“游戏中，余额将在本局结束后结算”，结束后通过手动刷新读取最终值。
- [游戏后端提交异常大的用量] → 校验字段并以 binding 可用余额封顶，同时记录请求值和实际扣减值供诊断。
- [多人结果部分送达] → 每个 participant play 独立进入 outbox 和幂等结算，后续重试补齐，不回滚已经可靠完成的 participant。
- [数据库迁移时仍有旧游戏运行] → 升级说明要求先停止三端并备份 SQLite；迁移脚本对旧 `ACTIVE` 明确归一化。

## Migration Plan

1. 备份现有 SQLite，并停止所有连接该平台的旧版游戏端。
2. 先发布会员平台数据库迁移和兼容读取逻辑。
3. 发布携带 `runningDurationMillis` 的游戏后端与对应前端。
4. 执行隔离 SQLite 迁移测试及三端核心流程测试，再用测试会员人工验证准备取消、自然结束、人工结束和余额耗尽。
5. 如需回滚，先停止新版游戏端，再恢复升级前 SQLite 备份和三端旧版本；新旧计费模型不支持边运行边降级。

