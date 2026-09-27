## Why

客户提供的两份 Grid 项目采用同一套“Style A · 经典赛博霓虹”设计，当前游戏端主要采用浅色拟物样式，需要统一为客户指定的视觉风格。此次仅调整展示层，避免影响已验证的游戏、编辑器和硬件链路。

## What Changes

- 参考 `F:/project/Grid-Dev-Handoff-Style260923` 的规范及布局示例，以及 `F:/project/Grid-Vue-Component-Library-Style` 的 Vue 组件样式，统一深紫黑背景、青色/品红强调色、玻璃面板、边框、字体层级和交互状态。
- 覆盖主窗口标题栏及导航、分类首页和游戏列表、媒体库和精灵库、Simple/Normal/Diffcult 与 Rank 编辑器、配置/帮助/语言/SDK 页面及各类弹窗。
- Touch、DebugPanel 和副屏只统一非内容区域的控件、面板和信息展示样式，保留现有窗口职责及操作流程。
- 保持所有业务逻辑、功能入口、文本语义、数据绑定、校验、保存、接口和 IPC 不变。
- 不替换、不修改现有应用图标、封面、精灵、图片、音视频、自定义背景及待机动画；不导入参考项目的演示数据或占位图片。
- 增加样式验收与功能回归，重点检查焦点、弹窗、矩阵尺寸、滚动布局和多窗口隔离。

## Capabilities

### New Capabilities

- `game-client-cyber-neon-presentation`: 游戏端统一赛博霓虹视觉规则及不改变业务、资源和待机动画的展示层约束。

### Modified Capabilities

无。现有业务能力的要求不变。

## Impact

- 规划材料放在 `ledGame-platform/openspec/changes/restyle-game-client-cyber-neon`，实际实施仅涉及 `F:/project/ledGame` 的前端样式、展示模板及相关测试。
- 主要涉及 `src/style.css`、`src/App.vue`、`src/views/*.vue` 与 `src/components/*.vue`；按需要新增小型设计变量文件，不引入独立主题框架。
- 不修改 `ledGame-backend`、会员管理端、自助注册端、数据库结构、HTTP/IPC 协议或打包混淆配置。
- 不依赖联网字体、CDN 或参考项目在运行机器上的绝对路径；现有离线打包方式保留。
