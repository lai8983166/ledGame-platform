## 1. SQLite 余额模型与迁移

- [ ] 1.1 先补充隔离 SQLite 迁移测试，覆盖 `CHARGED`、`READY`、旧 `ACTIVE`、`EXPIRED` 和 `EMPTY` 的换算规则，并验证测试不读写人工操作数据库
- [ ] 1.2 为 open binding 增加 `remaining_seconds`，为 game play 增加 `running_duration_millis` 和 `consumed_seconds`，实现幂等迁移并通过 1.1 的测试
- [ ] 1.3 更新充值、绑定、回收、清零和状态查询的数据访问逻辑，验证新充值按分钟初始化秒数且余额永不为负

## 2. 准入与结算业务

- [ ] 2.1 先补充服务测试，验证刷卡校验和取消 preparation 不扣时、confirm 只创建 play 与占用手环、零余额拒绝准入
- [ ] 2.2 修改 game access 接口与共享 DTO，移除连续 `expiresAt` 作为权威余额的语义，并通过准入契约测试
- [ ] 2.3 先补充结算测试，覆盖自然结束、人工结束、`STARTUP_ABORT` 零用量、`TIME_BALANCE_EXHAUSTED`、毫秒整局向上取整和余额上限
- [ ] 2.4 实现携带 `runningDurationMillis` 的原子结算，使 play 终态、积分、用量和 binding 的 `ACTIVE→READY/EXPIRED` 在同一事务完成，并通过 2.3 的测试
- [ ] 2.5 补充同一 `playId` 重试及两名玩家相同用量的集成测试，验证积分和余额均只处理一次

## 3. 管理端、自助端与查询

- [ ] 3.1 更新 Player Info、会员列表、手环管理和运营查询 DTO，验证返回持久化剩余游戏秒数及准确的 `READY/ACTIVE/EXPIRED` 状态
- [ ] 3.2 调整会员管理端手环与会员 UI，显示“剩余游戏时长”“游戏中，结束后结算”及手动刷新结果，并用组件测试验证不再展示连续到期倒计时
- [ ] 3.3 调整自助注册端玩家信息查询，验证刷卡准备不会在页面本地扣时且结算后重新查询得到新余额

## 4. 跨端验收与文档

- [ ] 4.1 扩展 `pnpm test:core`，验证仅显式 `RUNNING` 用量会扣时、墙钟推进不会扣时、重复结果不会重复扣时
- [ ] 4.2 扩展 `pnpm test:e2e`，覆盖自然结束、Debug Panel 人工结束、启动失败、多人相同扣时、平台暂时离线后的结果补交和 Player Info 最终余额
- [ ] 4.3 更新中文大型测试说明与报告字段，记录各生命周期耗时、提交用量、实际扣减和断言结果，并运行命令确认报告可重复生成
- [ ] 4.4 运行平台单元/集成测试、前端构建和 `openspec validate deduct-wristband-time-only-while-running --strict`，记录验证结果并确认人工数据库未被修改

