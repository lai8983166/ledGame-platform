## Why

`simple`、`normal` 和 `diffcult` 目前共用同一个游戏编辑器，但还没有可视化配置外围符号灯和圆形 RGB 灯布局的入口。旧实现依赖不同的接口和保存模型，直接搬迁会覆盖现有未保存的矩阵/关卡编辑内容，因此需要在当前编辑器架构中增加一个隔离的布局配置弹窗，并复用现有的游戏编辑器保存链路。

## What Changes

- 在 `simple / normal / diffcult` 共用的游戏编辑器中增加“符号灯布局”按钮和独立弹窗。
- 支持配置启用状态、控制器、符号灯/圆灯通道、灯链方向、起点、四面墙灯数量和可选地砖对齐坐标。
- 提供仅由前端状态驱动的布局预览；“绘制”不发送硬件命令或 HTTP 请求。
- 将布局保存到现有 `commonConfig.pixelLightWiring`，不增加数据库表或独立接口。
- 保留关闭布局时已有的数量、起点和对齐数据；调整墙面数量时按稳定的 `wall|idx` 身份保留仍存在的行配置。
- 增加前端布局归一化、校验和保存保护测试，并覆盖后端编辑器文档的 round-trip 兼容性。

## Capabilities

### New Capabilities

- `game-pixel-light-layout-editor`: 在默认游戏编辑器中编辑、预览和持久化外围符号灯/圆灯布局。

### Modified Capabilities

无。

## Impact

- 前端：`F:/project/ledGame/src/views/SimpleGameEditorView.vue`，新增布局弹窗组件和纯布局状态工具模块。
- 后端：复用现有 `GameEditorDocument.CommonConfig.PixelLightWiring`、`/game-editor/{id}` 查询和保存接口；原则上不改数据库结构。
- Electron：不新增 IPC，继续使用现有 `getGameEditor` / `saveGameEditor` API。
- 运行时硬件输出：本 Change 不改变游戏运行时灯光报文；实际驱动符号灯和圆灯由后续独立 Change 负责。
