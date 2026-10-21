# 游戏端赛博霓虹样式验收

## 改动边界

采用两份 Grid 参考设计的深紫黑底色、青色/品红强调、细边框和深色面板。只调整 CSS，未修改 Vue 脚本和模板、Electron、接口、数据库、业务模块、图片及动画资源。无新运行时依赖、联网字体、主题框架或装饰计时器。

## 已完成检查

| 范围 | 验证 |
| --- | --- |
| 首页/游戏列表、媒体库、精灵库、语言、设置、同步、SDK 三页 | 1280×720、1920×1080 截图与输入检查 |
| Simple/Normal/Diffcult、Rank 编辑器 | 截图；编辑器工具切换矩阵位置及像素不变 |
| 全局配置、符号灯、特效弹窗 | 开关弹窗后输入可聚焦；修正浅色残留及原生灰色输入框 |
| Touch、DebugPanel、副屏 | 待机及运行状态截图；保留原待机组件，矩阵底行在视口内 |
| 高 DPI | 150% 缩放，两种视口尺寸下交互检查通过 |
| 资源与业务保护 | 自动比对 Git 基线：所有修改的 Vue 脚本/模板不变，矩阵/精灵/布线/待机组件及资源不变；设计变量引用完整 |

副屏模拟发送 100 次分数更新（20ms 间隔），前后均显示最终分数 99。渲染帧间隔 p50 均约 16.7ms、p95 均约 16.8ms，未见明显退化。这只是本机短时 UI 对比，不是硬件链路延迟或长期烤机结论。没有连接控制器，本次不声称完成真实硬件验收。

## 测试结果及原有问题

- 生产构建通过，仅有既有的 JS 包体积提示。
- Node 测试 356 项：354 通过、2 项失败，与修改前一致。失败在 `simpleObjectEditorControls.test.mjs`，旧模板断言未适配已有的基准编辑条件；本次未改其业务实现。
- `pnpm test` 的前置国际化检查仍被已有的 `App.vue` 中 Close/Maximize/Minimize 文案拦截。本次没有修改该文件，也没有跳过检查来宣称全绿。
- 更新一条标题栏样式测试，使其检查新主题变量而非旧渐变颜色。
- 交互检查正常 DPI 与 150% DPI 各 10 项通过。一次并行运行时精灵搜索等待超时，串行复跑通过；最终采用串行结果，不以并行超时推断业务错误。

## 本机证据

截图与隔离测试配置在游戏仓库 `.build/ui-review/`，修改前后分别为 `before/`、`after/`，交互截图和结果为 `interactions/`。测试没有操作营业数据库，设置输入检查不保存。脚本位于 `.build/ui-review.mjs`、`.build/ui-runtime-review.mjs`、`.build/ui-interaction-review.mjs`、`.build/verify-ui-boundary.mjs`；这些是本次本机验收材料，不是新增产品测试模块。

## 打包验收

2026-09-27 已更新原位置 `release/win-unpacked` 与 `release/LED Game-0.1.0-win.zip`（933,676,729 字节）。`portable:dist` 已完成后端资源准备和前端生产构建，但打包器重命名 Electron 临时目录时遇到 EPERM；随后以命令行 `--config.electronDist=node_modules/electron/dist` 使用相同版本的本地 Electron 完成 dir/zip 打包，没有修改仓库正式打包配置。

新 EXE 使用隔离数据目录启动成功，内嵌后端可返回 5 个游戏；断开浏览器网络后重新加载本地页面，主题样式正常且无页面异常。ZIP 与 `win-unpacked` 中 EXE、`resources/app.asar` 的 SHA-256 一致。未重打包会员管理端、自助注册端；未连接真实控制器做硬件测试。

## 2026-10-19 主窗口背景对齐

按 Vue 参考项目的实际 CSS 对齐：保留 `#0a0118`，去除自定义左右光晕，增加淡扫描线、60px 网格及径向遮罩。仅修改主窗口 `.app-shell`，装饰层在内容之后、不可接收鼠标事件；没有修改业务代码、图片或待机动画，也没有给 Touch、DebugPanel、副屏增加这些背景层。

- `node scripts/test-main-background-ui.mjs`：1280×720、1920×1080 和 125% DPI 共三组通过。读取参考项目真实 CSS，检查装饰参数一致；背景像素允许独立图层透明混合产生最多 2/255 的通道舍入差、差异通道少于 2%，图片与矩阵像素必须完全相同；点击、输入和其他窗口隔离通过。证据位于游戏仓库 `.build/main-background/`。
- `pnpm test`：431 项全部通过，国际化检查通过；此前初版报告中的旧失败不代表本次状态。
- `node scripts/test-game-ui-followup.mjs`：五组交互回归通过，覆盖精灵颜色/筛选、旋转、配置、副屏与 Touch。
- 生产构建及原位置 `win-unpacked`、`LED Game-0.1.0-win.zip` 更新成功，仅有既有的大 JS 包提示；两份产物 914 个文件逐项 SHA-256 一致。
- `node scripts/test-editor-flash-packaged.mjs`：真实 EXE 背景检查及八项原编辑器回归通过，页面错误为空；证据在 `.build/editor-flash-packaged-nywtME/`。`node scripts/test-settings-touch-packaged.mjs --settings-only` 五组通过，证据在 `.build/settings-touch-packaged-wUqUvO/`。两者均使用隔离数据库，未操作实际用户数据，未连接硬件。

## 2026-10-20 新版完整页面背景

按客户新增的`game清单/style-a-neon.html`替代上次Vue纯色基准：#0a0118上叠加顶部紫色18%、左下青色10%、右下品红12%三层原参数静态渐变。保留扫描线与60px网格，并按新参考让扫描线位于网格上方；均仅限主窗口、位于内容后方。不修改Vue业务、图片、矩阵或其他窗口，不新增动画、计时器或运行时外部路径依赖。

- 背景专项三组通过：1280×720、1920×1080、125% DPI。实际读取新参考CSS，渐变及装饰参数一致；纯渐变允许1/255合成舍入差，含透明装饰允许2/255，图片/矩阵像素须完全一致。验证顶部/左右/底部区域、点击、输入、其他窗口隔离，截图已查看。125% DPI裁剪边界使用整物理像素，避免截图越过视口边缘造成假差异；证据`.build/main-background/`。
- 前端432项测试及国际化检查通过，`test-game-ui-followup.mjs`五组交互通过；生产构建通过，只有既有大JS包提示。
- 已更新真实EXE背景检查脚本。检测到原位置游戏端仍在运行，未覆盖release；待用户保存并关闭后打包并执行真实EXE/ZIP一致性验收。本段不代表已完成打包。

用户确认关闭后已完成上述交付：原位置`release/win-unpacked`和`release/LED Game-0.1.0-win.zip`均已覆盖更新，914个文件逐项SHA-256一致。真实EXE背景检查和编辑器回归全部通过，页面错误为空，已查看`主窗口背景.png`；隔离证据`.build/editor-flash-packaged-JV3yEr/验收结果.json`。未操作实际用户数据库、未重打其他客户端、未提交Git。本Change25项任务完成。
