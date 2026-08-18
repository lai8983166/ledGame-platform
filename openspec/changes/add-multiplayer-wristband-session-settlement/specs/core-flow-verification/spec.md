## ADDED Requirements

### Requirement: 多人核心流程具有真实跨服务验收
项目 SHALL 使用真实 SQLite、真实游戏后端和 Electron 游戏端验证多人刷卡、共享游戏结果和逐会员积分，且测试数据 SHALL 与运营数据库隔离。

#### Scenario: 两人未刷够时禁止启动
- **WHEN** 验收流程选择两人但只提交一只有效手环
- **THEN** 游戏端 SHALL 保持不可启动
- **AND** 会员平台 SHALL 不存在该局的部分 play records

#### Scenario: 两人自然完成共享游戏
- **WHEN** 两位不同会员完成刷卡并自然结束同一局
- **THEN** SQLite SHALL 保存两条共享 external session 的 play records
- **AND** 两条记录 SHALL 具有相同 raw score 和 points awarded
- **AND** 每位会员的 Player Info SHALL 只增加自己的记录和积分

#### Scenario: 三个 Simple 变体均通过多人验收
- **WHEN** 验收套件参数化运行 `simple`、`normal` 和 `diffcult`
- **THEN** 每个变体 SHALL 使用共享玩法结果为所有参与者各结算一次
- **AND** Debug Panel 与副屏契约 SHALL 保持共享状态展示

### Requirement: 多人核心规则测试优先
每项多人准入、批量建档、幂等或结算规则 SHALL 先由失败自动化场景证明缺失，再实施代码并通过聚焦及完整核心测试。

#### Scenario: 修改多人核心规则
- **WHEN** 开发者新增或修复多人会员游戏规则
- **THEN** 对应测试 SHALL 在实现前失败并在实现后通过
- **AND** `pnpm test:core`、平台 server tests 和大型验收 SHALL 全部通过
