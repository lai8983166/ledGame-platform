## Why

`server/` 当前只有一个 Spring Boot 启动入口 `PlatformApplication.java`，没有任何业务模块。会员和手环是整个系统的根——账户、记录、游戏、跨端同步都依赖它们。在动手实现之前，先把数据模型、状态机和关键决策沉淀下来，避免后续返工和重做。

本 change 是 platform 后端建设的起点，建立"会员 + 手环"业务域的最小完整形态。

## What Changes

- 在 `server/` 新增"会员 + 手环"业务域，包含 `Member`、`Wristband`、`WristbandBinding` 三个聚合根。
- 定义手环完整状态机：`IN_STOCK / BOUND / ACTIVE / USED_UP / RETIRED`。
- 定义绑定关系状态机：`BOUND / ACTIVE / USED_UP / CANCELLED`。
- 实现核心流程：入库、发卡、时长充值、激活、到期结束、归还、退役、取消、续杯。
- 明确关键决策：手机号唯一、IC UID 作为物理标识、一个会员可同时多绑定、duration 由调用方传入而非全局配置、无挂失功能。
- 暴露供 kiosk、admin 和游戏桌面端调用的服务接口（具体 API 形态留给后续 change，本 change 只定行为契约和数据模型）。

## Capabilities

### New Capabilities

- `member-wristband`: 会员档案、手环库存、绑定关系及其完整生命周期管理。

### Modified Capabilities

- 无。

## Impact

- 主要影响 `server/` 的包结构，新增 `member`、`wristband` 域及其持久化映射。
- 不影响 `apps/registration-kiosk` 和 `apps/member-admin` 已完成的 UI（它们仍使用本地模拟数据，后续接线由独立 change 处理）。
- 不引入新的基础设施依赖（先以单体 + 单库起步；Redis、MQ 等暂不引入）。
- 暂不实现账户扣款、跨端同步、认证授权、审计查询界面——这些由独立的后续 change 承担。本 change 只保证状态变更时输出可被消费的领域事件（事件结构由 Records / Sync change 定义）。
