## Why

当前正式游戏启动检查、地砖输出与符号灯生命周期处于公共运行链路中；全局启用 ELC408 后，未来不使用地砖的玩法也会被控制器状态影响。需要把这些职责交给使用它们的玩法，同时保持现有游戏和三端业务行为。

## What Changes

- 地砖玩法负责 ELC 启动检查、踩踏输入、地砖帧输出和外围灯初始化/清理；通用引擎通过现有玩法扩展点调度这些操作。
- Simple、Normal、Diffcult、Rank 复用一份地砖硬件实现，继续使用现有 SDK、物理布线、报文、调试输入和符号灯配置。
- 后续不使用地砖的玩法可以通过现有 runtime factory 注册，不必初始化 ELC、检查控制器或接收地砖输入。
- 移出引擎中的具体地砖待机输出与符号灯调用，保留现有 16×36 彩虹待机、准备页矩阵及媒体输出行为。
- 补充有硬件/无硬件玩法隔离、启动失败清理、连续切换及现有业务回归验收。
- 玩法允许直接使用具体硬件；本次不建立通用硬件插件框架，不增加硬件配置表、每个游戏的设备选择 UI 或逐游戏布线副本。

## Capabilities

### New Capabilities

- `gameplay-owned-hardware`: 硬件检查、输入输出和资源生命周期按当前玩法执行，现有地砖玩法行为保持兼容。

### Modified Capabilities

无。已有会员、计时、积分、排队与编辑能力的要求不变。

## Impact

- Change 规划位于 `ledGame-platform/openspec/changes/decouple-game-engine-from-floor-hardware`。
- 实际实现主要涉及 `F:/project/ledGame-backend` 的 `GameLaunchService`、runtime factory/handler、`GameEngine`、`Stage`、地砖媒体输出、输入入口和外围灯生命周期，以及对应测试。
- 复用现有 ELC SDK/TCP 链路和硬件健康上报；不改变控制器协议或搜索周期。
- 游戏端现有 HTTP/IPC、运行时状态字段和前端入口保持兼容，无数据库迁移或新增依赖。实施完成后更新游戏端原位置 `win-unpacked` 和 ZIP。
- 会员管理端、自助注册端只作为核心流程回归对象，本次不规划业务代码修改。
