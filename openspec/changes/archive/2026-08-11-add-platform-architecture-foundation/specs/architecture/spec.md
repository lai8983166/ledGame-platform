## ADDED Requirements

> 本 spec 定义所有后续业务模块必须遵守的架构约束。约束来源是 `decisions.md` 中状态为 ✅ 或 ⚠️ 的决策项。
> ❓ 待定项不进入本 spec，等决策确认后再补充。

### Requirement: 单门店独立部署
系统 SHALL 以"单门店独立部署"为基本运行形态：每家门店拥有完整的 kiosk、member-admin、游戏桌面端、中心后端和数据库实例，门店之间不共享数据，不依赖跨门店的中心服务。

#### Scenario: 一家门店故障不影响其他门店
- **WHEN** 门店 A 的中心后端宕机
- **THEN** 门店 B 的所有业务不受影响
- **AND** 门店 A 的故障不会扩散到 B 的数据或服务

### Requirement: 单体应用 + 包级模块
系统后端 SHALL 以单一 Spring Boot 应用部署，业务域（会员、手环、账户、房间、游戏、记录、同步、配置）以 Java 包形式内嵌，**不**采用微服务拆分。

#### Scenario: 单进程承载所有业务域
- **WHEN** 任意业务模块被调用
- **THEN** 请求由同一个 Spring Boot 进程处理
- **AND** 不存在跨进程的内部 RPC 或服务间调用

### Requirement: 不引入 Redis / MQ 等额外中间件
系统 SHALL 仅依赖 MySQL 与 Spring Boot 内置组件。**禁止**引入 Redis、RabbitMQ、Kafka、RocketMQ 等额外中间件，除非有新的决策项明确推翻此约束（见 `decisions.md` A3）。

#### Scenario: 实时临时数据存放在进程内存
- **WHEN** 房间状态、硬件状态、当前积分等实时数据被写入或读取
- **THEN** 数据存放于 Spring Boot 应用进程内存
- **AND** 进程重启后这部分数据丢失（业务上可接受）

#### Scenario: 跨端可靠事件投递不使用 MQ
- **WHEN** 中心端需要向游戏桌面端可靠投递事件
- **THEN** 投递机制基于 MySQL 中的事务消息表 + 应用内 Worker 重试
- **AND** 不引入 MQ 中间件

### Requirement: 最低限度认证必须存在
系统 SHALL 对所有非公开接口要求身份认证，区分三类调用方：管理员（账号密码登录）、kiosk 设备（预置令牌）、游戏桌面端设备（预置令牌）。**禁止**完全匿名调用业务接口（见 `decisions.md` C1）。

#### Scenario: 未认证请求被拒绝
- **WHEN** 任一调用方未提供有效身份凭证
- **THEN** 系统返回 401 Unauthorized
- **AND** 不执行任何业务逻辑

#### Scenario: 操作来源可追溯
- **WHEN** 任意变更类操作（创建、修改、删除、状态转换）发生
- **THEN** 系统记录 `who`（管理员 ID 或设备 ID）+ `when` + `what` + `from_device`
- **AND** 审计日志可被 `records` / `audit` 模块消费

### Requirement: 高可用等级目标
系统 SHALL 满足营业时段 RTO ≤ 10 分钟、RPO ≤ 24 小时的可用性目标（见 `decisions.md` A6）。

#### Scenario: 进程崩溃自动恢复
- **WHEN** Spring Boot 进程异常退出
- **THEN** systemd 或等价监督进程在 1 分钟内自动重启
- **AND** 已提交的事务不丢失

#### Scenario: 每日数据备份
- **WHEN** 到达每日备份时间
- **THEN** 系统自动执行 MySQL 全量 dump
- **AND** 同时归档自上次备份以来的 binlog
- **AND** 备份保留期不少于 30 天

### Requirement: 实时推送走 WebSocket
系统 SHALL 使用 WebSocket 作为中心端到游戏桌面端的实时事件推送通道。HTTP 用于请求/响应式通信（见 `decisions.md` B1、B3）。

#### Scenario: 中心向桌面端推送实时事件
- **WHEN** 中心端产生需要实时通知桌面端的事件（如手环激活、立即生效的状态变更）
- **THEN** 事件通过 WebSocket 推送至已连接的桌面端
- **AND** 推送失败时回退到消息表 + Worker 重试

### Requirement: API 风格统一为 RESTful + JSON
系统 SHALL 对外暴露 RESTful HTTP API，请求/响应体使用 JSON。所有时间字段使用 ISO 8601 格式（见 `decisions.md` B1、B5）。

#### Scenario: 标准资源化 URL
- **WHEN** 调用方访问任意业务资源
- **THEN** URL 形如 `/api/<resource>/<id>`，使用标准 HTTP 方法表达动作
- **AND** 响应体为 JSON

### Requirement: 字符集与编码
系统数据库 SHALL 使用 `utf8mb4` 字符集与 `utf8mb4_unicode_ci` 排序规则。所有 API 字符串使用 UTF-8 编码（见 `decisions.md` D1）。

#### Scenario: 存储与传输 Unicode 字符
- **WHEN** 任意中文字符、emoji 或多字节字符进入系统
- **THEN** 数据库正确存储与查询
- **AND** API 正确序列化与反序列化

### Requirement: 数据库迁移版本化
系统 SHALL 使用版本化迁移工具（Flyway）管理数据库 schema 变更，禁止手工直接修改生产 schema（见 `decisions.md` D6）。

#### Scenario: 部署时自动执行迁移
- **WHEN** 应用启动
- **THEN** Flyway 自动检查并应用未执行的迁移脚本
- **AND** 迁移历史保留在专用表中
