## Why

会员平台当前以 `(device_id, external_session_id)` 限制一局只能有一条游玩记录，因此无法表达多位会员共同参与同一局并分别获得积分。需要在保留单人接口兼容和逐 play 幂等结算的同时，为多人游戏提供原子建档和每位会员独立积分记录。

## What Changes

- 为同一设备和 `externalSessionId` 支持多条按 binding/会员区分的游玩记录，并继续禁止同一手环并发参与其他运行中游戏。
- 提供多人 play 原子启动契约：一次请求验证所有参与者均已激活、有余额、互不重复且可用，全部成功才创建本局的多条 `RUNNING` 记录。
- 同一批次重试返回既有参与者 play 集合，不重复建档；参与者集合冲突时返回稳定业务错误。
- 保持现有单 play 结果接口，以每个 platform `playId` 独立结算同一份共享 `rawScore`、success、termination reason 和 payload。
- 每条 play 独立执行现有积分策略，因此 `simple`、`normal`、`diffcult` 同局中的每位会员获得相同积分；重复回调不得重复加分。
- Player Info、会员积分、排名和游玩记录按现有查询自然包含每位玩家自己的记录。
- 增加真实 SQLite 的多人建档、幂等、并发占用、逐玩家结算与跨端大型验收测试，并先写失败场景再实施。
- 不新增个人游戏内分数、玩家位置、组队排名或 Debug Panel/副屏展示数据。

## Capabilities

### New Capabilities

### Modified Capabilities
- `game-play-records`: 从每个 external session 单条 play 扩展为同一 external session 下每位参与者一条 play，并提供原子批量启动与独立幂等结算。
- `game-access-entitlement`: 多人启动时校验全部互不重复的会员与手环访问窗口，任一无资格参与者使整批启动失败关闭。
- `core-flow-verification`: 增加多人刷卡、三个 Simple 变体共享结果和逐会员相同积分的真实 SQLite 与跨服务验收覆盖。

## Impact

- 影响 SQLite schema/migration、`GamePlayService`/controller、game access 校验、Player Info 数据验证和 server/acceptance tests。
- API 将增加多人 play 启动契约；现有单人启动和单 play 结算接口保持兼容。
- 与 `ledGame-backend` 的 participant/play DTO 及幂等规则必须同步交付。
