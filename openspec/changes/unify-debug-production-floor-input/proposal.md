## Why

当前大型验收通过 Debug Panel 的一次性 `click` 输入完成游戏，而真实游戏模式接收的是地砖 `DOWN/UP` 事件。两条路径虽然共享得分、关卡判定和结算逻辑，但验收尚不能发现地砖时序、输入重置、适配器接线或生产模式硬件就绪分支中的回归，因此需要收紧输入契约和测试边界。

## What Changes

- 定义与输入来源无关的规范化地砖输入契约，明确坐标、按下、抬起和输入会话重置语义。
- 让 Debug Panel 的正常游戏交互模拟与真实地砖相同的 `DOWN -> UP` 输入序列，不再以专用 `click` 快捷路径作为大型验收的正常完成方式。
- 保留 `End Game`、强制关卡成功、重试和跳关等调试指令，但明确它们是运维/调试覆盖，不作为游戏规则正确性的证明。
- 增加适配器契约测试，分别验证 Debug 输入和 ELC-408/TCP 输入能够产生等价的规范化事件。
- 增加规则一致性测试：同一游戏夹具经 Debug 和地砖输入后产生相同的得分、生命、关卡结果、终止原因和结算快照。
- 将跨端黄金流程改为通过规范化地砖事件自然完成游戏，并增加不依赖真实控制器的生产形态测试，覆盖 `PRODUCTION` 启动、就绪和输出边界。
- 继续将真实控制器、物理地砖、电气和现场网络验证保留为独立的现场冒烟检查，不声称自动化测试可替代硬件验收。

## Capabilities

### New Capabilities

- `game-floor-input-contract`: 规范 Debug、ELC-408 和游戏运行时之间统一的地砖输入语义、适配边界及行为一致性。

### Modified Capabilities

- `core-flow-verification`: 将核心流程验收从 Debug `click` 快捷输入收紧为规范化地砖输入，并增加无真实硬件的生产形态覆盖及清晰的硬件验收边界。

## Impact

- `F:/project/ledGame`：Debug Panel 交互、Electron IPC 输入接口、稳定测试选择器及前端聚焦测试。
- `F:/project/ledGame-backend`：`GameInput`/地砖输入模型、ELC-408/TCP 适配器、运行时分发、Simple Game 处理器以及生产模式测试替身。
- `F:/project/ledGame-platform`：跨仓库 Playwright 验收编排、中文报告说明和核心流程断言。
- 不引入新的中间件、云服务或生产数据库；对外会员平台接口不变。
