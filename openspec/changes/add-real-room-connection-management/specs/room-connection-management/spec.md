## ADDED Requirements

### Requirement: 游戏桌面端建立房间长连接

系统 SHALL 允许游戏桌面端 backend 主动与会员管理端建立经过认证的 WebSocket 长连接。会员管理端 SHALL 使用连接的实际来源 IP 识别房间，而不是使用客户端任意声明的 IP。

#### Scenario: 游戏桌面端成功连接

- **WHEN** 已登记设备使用有效凭证建立 WebSocket 连接
- **THEN** 会员管理端 SHALL 将该来源 IP 对应的房间标记为 `ONLINE`，分配连接会话标识，并返回连接确认消息

#### Scenario: 未登记或认证失败的连接

- **WHEN** 连接凭证无效或来源 IP 不属于允许的房间
- **THEN** 会员管理端 SHALL 拒绝连接，且不得修改任何已登记房间的在线状态

### Requirement: 房间在线状态随连接生命周期变化

会员管理端 SHALL 在连接建立、正常关闭、异常断开和重连时更新房间在线状态。系统 MUST 支持同一来源 IP 只有一个有效连接会话。

#### Scenario: 连接断开

- **WHEN** 房间 WebSocket 连接关闭或传输层检测到连接失效
- **THEN** 会员管理端 SHALL 将该房间标记为 `OFFLINE`，并保留最后一次有效运行状态作为诊断信息

#### Scenario: 同一 IP 重连

- **WHEN** 同一来源 IP 建立新连接
- **THEN** 系统 SHALL 使旧连接会话失效，只接受新连接会话发送的后续事件

### Requirement: 连接建立后同步房间快照

游戏桌面端 SHALL 在连接建立和重连后发送 `ROOM_SNAPSHOT`。快照 SHALL 至少包含设备 IP、引擎状态、当前游戏标识和排队数量。

#### Scenario: 重连恢复状态

- **WHEN** 游戏桌面端断线后重新连接并发送当前快照
- **THEN** 会员管理端 SHALL 使用快照恢复该房间的最新运行状态，而不要求客户端补发断线期间的每一条历史事件

### Requirement: 接收关键房间事件

系统 SHALL 支持接收 `GAME_STARTED`、`QUEUE_CHANGED` 和 `GAME_ENDED` 事件，并更新对应房间 Card 的当前游戏、游戏状态和排队数量。第一阶段 SHALL NOT 要求事件包含积分或完整游戏记录。

#### Scenario: 游戏开始

- **WHEN** 游戏桌面端完成一局游戏启动并发送 `GAME_STARTED`
- **THEN** 会员管理端 SHALL 将房间状态更新为运行中，并记录当前游戏标识

#### Scenario: 排队变化

- **WHEN** 游戏桌面端成功增加、取消或提升排队项目并发送 `QUEUE_CHANGED`
- **THEN** 会员管理端 SHALL 更新房间 Card 的排队数量

#### Scenario: 游戏结束

- **WHEN** 游戏桌面端完成或停止当前游戏并发送 `GAME_ENDED`
- **THEN** 会员管理端 SHALL 将房间运行状态更新为待机或结束状态，且不得仅因为该事件自动修改积分或游戏记录

### Requirement: 事件顺序和幂等处理

每个房间事件 SHALL 携带连接会话标识和递增事件序号或幂等事件 ID。会员管理端 SHALL 忽略重复事件和已经处理过的旧事件，避免房间状态回退。

#### Scenario: 重复事件

- **WHEN** 因网络重试导致同一个事件被发送两次
- **THEN** 会员管理端 SHALL 只产生一次状态变更，并向客户端返回幂等确认

#### Scenario: 旧事件乱序到达

- **WHEN** 较小序号的事件在较大序号事件之后到达
- **THEN** 会员管理端 SHALL 忽略该旧事件，保留较新事件产生的房间状态

### Requirement: 房间 Card 展示真实连接和运行状态

会员管理端 SHALL 根据连接生命周期显示房间在线状态，并根据最后一次有效快照或关键事件显示当前运行状态、当前游戏、排队数量和最后事件时间。

#### Scenario: 在线房间展示

- **WHEN** 房间存在有效 WebSocket 会话和最近一次有效状态
- **THEN** 房间 Card SHALL 显示 `ONLINE` 以及对应的运行状态和排队数量

#### Scenario: 离线房间展示

- **WHEN** 房间没有有效 WebSocket 会话
- **THEN** 房间 Card SHALL 显示 `OFFLINE`，保留最后状态用于诊断，并不得把离线自动解释为游戏正常结束
