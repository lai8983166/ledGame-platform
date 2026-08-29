## 1. 游戏后端统一计时状态

- [x] 1.1 先扩展 `GameRuntimeStateServiceTest`，覆盖有限时间运行、无限时间、暂停冻结、剩余归零和无活动游戏的 `gameTime` 契约，并确认新增断言在实现前失败
- [x] 1.2 在通用运行时快照中实现 `gameTime.mode/remainingMillis/running` 投影，复用引擎排除暂停时段后的总运行时间，并运行 `mvn -Dtest=GameRuntimeStateServiceTest test` 验证通过
- [x] 1.3 先为房间连接增加游戏开始、计时暂停、计时恢复、游戏结束和运行中重连快照的事件测试，验证每个关键节点携带最新计时锚点且不存在逐秒房间事件
- [x] 1.4 在统一生命周期/计时转换点发布房间计时状态事件，避免各玩法分别接线，并运行 `RoomConnectionClientTest`、`GameEngineStageProgressionTest` 和运行时广播测试验证暂停恢复及结束行为
- [x] 1.5 运行游戏后端完整 `mvn test`，确认现有玩法、调试模式、自然结束和手动结束流程没有回归

## 2. 游戏副屏计时展示

- [x] 2.1 先扩展运行时归一化和副屏 presentation 测试，覆盖有限、无限、暂停、结束、缺失字段、不足一秒及超过一小时格式，并确认新增断言在实现前失败
- [x] 2.2 在游戏端归一化统一 `gameTime` 投影并实现纯计时格式化函数，运行对应 Node 单元测试验证边界规则
- [x] 2.3 修改副屏 HUD，使所有玩法显示统一全局游戏时间；有限时间实时倒计时、暂停冻结、无限显示“无限”、空闲或结束隐藏，同时保留 Rank 局部回合数据但不再冒充全局剩余时间
- [x] 2.4 运行 `pnpm test` 和 `pnpm build`，并用副屏组件测试确认有限与无限状态不会显示原始毫秒、`00:00` 假无限或翻译键

## 3. 平台房间计时契约

- [x] 3.1 先扩展 `RoomConnectionRegistryTest` 和房间 WebSocket 集成测试，使用包含 `gameTime` 的 `GAME_STARTED`、计时暂停/恢复事件及 `ROOM_SNAPSHOT`，确认平台保留最新计时状态并以平台接收时间更新事件锚点
- [x] 3.2 如现有通用 JSON 状态投影不足，调整平台房间状态模型/接口以无损返回可选 `gameTime`，并运行 `mvn -q -f server/pom.xml -Dtest=RoomConnectionRegistryTest,RoomConnectionWebSocketIntegrationTest test`
- [x] 3.3 先扩展共享 API 客户端和 `roomStatus` 测试，覆盖有限运行、有限暂停、无限、空闲、结束、旧快照缺失字段、归零和长时长格式，并确认新增断言在实现前失败
- [x] 3.4 更新共享 `RoomStatus` 类型和会员管理端房间映射，保留计时模式、锚点剩余时间、运行状态及平台事件时间，并用纯函数按当前本地时间派生显示值
- [x] 3.5 修改房间 Card 和详情抽屉，使用本地一秒 tick 刷新有限倒计时、显示“无限”或兼容占位，并让已打开详情始终从最新房间集合派生；通过组件结构测试确认 tick 不会增加房间 API 请求频率
- [x] 3.6 运行 `pnpm test:client`、`pnpm typecheck:member-admin` 和 `pnpm build:member-admin`，确认房间筛选、改名、在线状态和详情抽屉没有回归

## 4. 跨端验收与文档

- [x] 4.1 增加跨仓库契约样例或验收场景，验证游戏后端输出的有限/无限 `gameTime` 能经房间 WebSocket 和平台 API 被会员管理端无损消费
- [x] 4.2 在测试中覆盖有限游戏自然倒数、结算暂停、恢复下一关、手动结束、无限游戏和运行中断线重连，确认副屏与房间管理在允许的网络延迟范围内显示一致语义
- [x] 4.3 更新中文验收说明与测试报告生成内容，写明该数值是本局全局游戏时间而非手环余额，并记录三端测试命令和手动核对步骤
- [x] 4.4 运行 `openspec validate display-global-game-time --strict` 以及三个仓库的相关完整测试，汇总结果并确认所有任务勾选后再进入归档
