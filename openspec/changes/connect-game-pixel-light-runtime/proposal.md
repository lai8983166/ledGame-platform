## Why

布局编辑器保存的数据目前不会影响游戏运行，`PixelLightDisplayUtils` 仍为空实现，运行时只输出地砖矩阵。因此即使管理员配置了符号灯和圆灯布局，真实外设也不会按游戏状态变化，需要在不破坏现有地砖输出的前提下把布局接入当前控制器报文链路。

## What Changes

- 在游戏启动时读取当前游戏的 `commonConfig.pixelLightWiring`，生成本局不可变的外围灯运行配置。
- 将符号灯状态和圆灯 RGB 状态映射到配置的控制器与通道，并接入当前游戏后端的输出链路。
- 保持现有地砖矩阵报文和控制器连接行为不变，支持多个控制器和不同通道组合。
- 布局关闭、缺失或非法时安全跳过外围灯输出，不阻断现有地砖游戏。
- 在游戏结束、取消、异常停止和下一局开始时清理上一局外围灯状态，避免残留灯光。
- 增加报文编码、通道映射、配置异常和清理时序测试，并提供无需真实灯具的假输出验收边界。

## Capabilities

### New Capabilities

- `game-pixel-light-runtime`: 根据每个游戏的布局配置驱动符号灯和圆形 RGB 灯，同时保持现有地砖输出兼容。

### Modified Capabilities

无。

## Impact

- 游戏后端：`GameEngine`、`GameContext`、`EventPusher`、LED 报文编码和 `PixelLightDisplayUtils` 等运行时组件。
- 配置读取：复用已经保存的 `GameEditorDocument.CommonConfig.PixelLightWiring`，不增加数据库表。
- 控制器通信：沿用当前 `TcpLedOutputManager`/编码器链路；不能直接照搬旧源码中假设的 UDP 专用链路。
- 兼容性：现有只配置地砖或关闭外围灯的游戏必须保持原有行为；非法外围灯配置不能让游戏整体无法启动。
