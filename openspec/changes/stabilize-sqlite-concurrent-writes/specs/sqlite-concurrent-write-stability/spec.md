## Purpose

确保会员管理端在多台自助注册端和游戏端同时提交业务数据时，能够遵守 SQLite 的单写者约束完成有限等待和一致提交，并在资源确实繁忙时返回可识别、可排查的错误而不是无含义的通用失败。

## ADDED Requirements

### Requirement: SQLite runtime uses a bounded concurrency-safe policy
会员管理端后端 SHALL 在接受业务请求前，为正式数据库和隔离测试数据库应用统一的 SQLite 并发策略；该策略 MUST 允许短暂写锁竞争在有限时间内等待完成，且等待时间 MUST 可通过服务端配置调整。

#### Scenario: File database starts with the concurrency policy
- **WHEN** 会员管理端后端使用现有 SQLite 文件正常启动
- **THEN** 后端在健康状态可用前完成并发策略初始化，且不删除、重建或改写现有业务数据

#### Scenario: Lock wait is bounded
- **WHEN** 另一个数据库使用者持有写锁
- **THEN** 业务请求只在配置的上限内等待，且不会无限挂起 HTTP 请求

### Requirement: Independent concurrent writes complete consistently
会员管理端后端 SHALL 协调来自不同 HTTP 请求的 SQLite 写事务，使互不冲突的充值、会员注册、手环绑定、激活、开局和自然结算操作不会仅因同时执行而产生非预期 HTTP 500、SQLite 锁错误、数据缺失、意外重复、错误关联或积分偏差。

#### Scenario: Multiple terminals submit mixed flows concurrently
- **WHEN** 至少两个独立代理使用互不重复的会员、手环和游戏会话，同时执行注册流与游戏流
- **THEN** 每个成功业务操作恰好提交一次，全部最终数据与各自输入保持正确关联，且中心日志没有 `SQLITE_BUSY` 或 `database is locked` 错误

#### Scenario: Reads overlap queued writes
- **WHEN** 查询请求与多个业务写请求同时到达
- **THEN** 查询返回一个一致的已提交状态，且不会观察到事务的部分写入结果

### Requirement: Exhausted lock wait returns an explicit service error
会员管理端后端 MUST 将超过有限等待时间的 SQLite `BUSY` 或 `LOCKED` 结果转换为 HTTP 503，响应体 MUST 包含稳定错误码 `DATABASE_BUSY` 和中文提示，并且 MUST 保持失败事务的原子回滚。其他数据库异常 MUST NOT 被误报为 `DATABASE_BUSY`。

#### Scenario: External lock outlasts the configured wait
- **WHEN** 测试使用另一个 SQLite 连接持续持有写锁，时间超过会员管理端配置的等待上限
- **THEN** 业务接口返回 HTTP 503、错误码 `DATABASE_BUSY`，提示数据库正忙并检查是否有其他程序占用，且该业务操作没有留下部分数据

#### Scenario: Non-lock database failure occurs
- **WHEN** 数据库请求因为非 `BUSY`、非 `LOCKED` 原因失败
- **THEN** 后端保留其真实异常分类和日志证据，不返回 `DATABASE_BUSY`

### Requirement: Existing database and backup behavior remain compatible
新的 SQLite 并发策略 MUST 兼容已有数据库文件、在线备份、退出备份、数据库检查和导入流程；升级和正常重启 MUST NOT 要求用户手工迁移数据库，也不得改变正式库与测试库的隔离边界。

#### Scenario: Existing database is opened after upgrade
- **WHEN** 新版本首次打开旧版本创建且包含业务数据的 SQLite 文件
- **THEN** 原会员、手环、游戏和账号数据仍可读取，并可继续完成新的业务写入

#### Scenario: Backup overlaps business traffic
- **WHEN** 在线备份在业务请求持续读写期间开始
- **THEN** 备份得到可通过完整性检查的一致快照，业务库不损坏，且备份动作不会造成非预期数据库锁 HTTP 500

### Requirement: Concurrent write stability is regression-tested
项目 MUST 使用真实临时 SQLite 文件执行可重复的服务端并发集成测试，并 SHALL 使用打包版多点并发验收工具验证完整系统；验收不得通过降低为单代理、禁止混合流程或在客户端自动重试来规避并发。

#### Scenario: Automated real-SQLite regression runs
- **WHEN** 执行服务端核心测试
- **THEN** 测试并发提交多组唯一业务数据并核对记录数量、唯一性、会员手环关联、结算状态和积分，同时验证锁等待耗尽的 503 契约

#### Scenario: Packaged multipoint smoke is rerun
- **WHEN** 使用同一会员管理端打包程序和两个同时运行的代理执行 smoke
- **THEN** 验收报告显示所有计划流完成，非预期 5xx、SQLite 锁证据和字段差异均为零
