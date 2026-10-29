# 实施与验证记录

验证日期：2026-10-06。12 项需求均已实施；实际代码修改在游戏前端、游戏后端，会员管理端与自助注册端无产品改动。

## 实施范围

- 游戏导航固定显示“游戏”，点击展开首页/游戏列表；“进入游戏”位于副屏后。首页分类横向约 2:1，游戏列表扩展至可用宽度。
- 分类和游戏使用右上角六点拖动柄，落下自动保存，失败恢复原顺序；分类内重排保持其他分类及隐藏游戏的原槽位。
- 封面选择保留文件夹层级，支持同名文件、类型过滤、双击确认与取消；沿用相对路径及现有预览加载器。
- 去掉图片更换入口的清除按钮；媒体库使用独立文件夹图标，图片/GIF 预览的最大线性适配尺寸减半。
- 新增精灵可选择既有四色，后端保存原有 color 字段；库与点阵画布显示真实颜色，不改已有精灵颜色，不显示“自定义”标签。
- 语言选择为当前页面上的弹窗，成功、取消、当前语言与失败重试均不销毁编辑草稿，保留原语种与国旗。
- 后端仅增加分类排序接口、扩展精灵创建颜色；不新增表、不迁移数据库、不改玩法、SDK 或会员通信。

## 自动化结果

| 验证 | 结果 |
| --- | --- |
| 游戏前端 `pnpm test` | 363 通过，0 失败，0 跳过 |
| 游戏后端 `mvn -q test` | 618 通过，0 失败，0 错误，0 跳过 |
| `pnpm build` | 通过 |
| `pnpm i18n:check` | 通过 |
| OpenSpec 严格校验 | 通过 |
| 浏览器真实界面交互 | 14 项通过，无页面错误 |
| 真实打包 EXE 隔离冒烟 | 8 项通过，包括关闭后重新启动 |
| ZIP / win-unpacked SHA-256 对比 | 916 个文件一致，无缺失、额外或内容差异 |

界面测试运行实际 Vue 组件，覆盖原生鼠标拖动、取消、保存期间禁止重复排序、故意保存失败后的回滚、两层目录及音频过滤、过期预览不能覆盖新图、语言错误重试和输入焦点、四色画布和旧色号回退。浏览器使用模拟接口以稳定触发错误分支；打包冒烟另外运行真实 IPC、内置 Java 后端和隔离 H2 数据库，验证分类、完整列表、分类内列表排序，以及封面和颜色的重启保留。

后端新增集成测试使用独立 H2，验证排列非法时不写入、事务中途失败全部回滚、分类元数据不变，及四色保存、非法颜色拒绝、缺省色 0、点位更新保色。前端测试执行真实 preload 和 main IPC 处理函数检查传参，不仅检查模板字符串。

## 布局与图片入口复核

1366×768、1920×1080、2560×1440 均检查分类整体宽高比约 2:1、游戏网格可用宽度、间距和无横向溢出，并输出首页/游戏列表截图。已查看 1366 游戏列表和打包版精灵截图，未见按钮重叠或内容遮挡。

图片清除入口清单：

- 游戏信息封面、分类封面。
- 应用图标（原本无清除）、副屏背景、副屏待机画面。
- Simple / Normal / Diffcult 共用全局配置的封面及五项 GIF 字段。
- Rank 媒体配置的待机、关卡失败/结算、游戏失败/完成、关卡开始画面。

以上保留选择、替换和预览；取消保留旧值。音频清除、画布操作、SDK 日志清除和 Touch 密码键盘清空保留。没有删除资源或兼容 IPC/API。

## 可复现命令与证据

在 `F:/project/ledGame` 运行：

```text
pnpm test
pnpm build
pnpm i18n:check
node scripts/test-catalog-library-ui.mjs
node scripts/test-catalog-library-packaged.mjs
```

后端在 `F:/project/ledGame-backend` 运行 `mvn -q test`；平台目录运行 `openspec validate improve-game-client-catalog-and-library-ui --strict`。

本次证据位置：

- 前端 `.build/catalog-ui-front-tests.log`。
- 后端 `target/surefire-reports/` 与 `target/catalog-ui-all-tests.log`。
- 界面结果及六张布局截图：游戏前端 `.build/catalog-library-ui/`。以 `results.json` 为本次结论，旧调试失败截图不作为本次证据。
- 打包冒烟：游戏前端 `.build/catalog-packaged-82fmAS/results.json`，同目录保留隔离数据库、日志和精灵截图。
- 文件对比：游戏前端 `.build/catalog-zip-consistency.json`。

测试未访问或修改人工使用的游戏数据库，未连接真实控制器；本次不宣称进行了硬件验收。

## 打包交付

游戏端 `release/win-unpacked` 与 `release/LED Game-0.1.0-win.zip` 已更新。标准 `pnpm portable:dist` 完成后端、资源及前端构建，但 Electron 临时目录重命名遇到 Windows EPERM；最后使用同版本本机 Electron 完成打包：

```text
pnpm exec electron-builder --config electron-builder.json --config.electronDist=node_modules/electron/dist --win dir zip
```

该参数仅用于本次构建，不修改永久打包配置。两种交付产物已逐文件校验。会员管理端和自助注册端不需重新打包；本次未提交 Git、未归档 Change。

## 2026-10-09 追加：首页卡片上封面、下名称

分类卡片内部从左右排布改为上封面、下名称，删除额外进入提示。整体仍为约 2:1；长名称允许换行，右侧为编辑按钮预留空间。拖动柄、编辑、进入分类、持久化与游戏卡片逻辑未改。

- 先增加真实组件上下位置断言，旧布局在“名称必须位于封面下方”断言失败；修改后通过。
- `pnpm test`：381 项全量通过，含 i18n 检查。
- `node scripts/test-catalog-library-ui.mjs`：14 组通过；1366×768、1920×1080、2560×1440 验证上下排布、比例、编辑按钮不重叠、长名称换行及无横向溢出，已查看 1366px 截图。
- `pnpm build`：通过；后端无改动，沿用已准备的 JRE、后端和媒体资源，未重跑后端全量。
- `node scripts/test-catalog-library-packaged.mjs`：9 项隔离验收通过，包含三种窗口尺寸上下布局，以及真实排序/封面保存、语言草稿、四色精灵、关闭重启持久化；证据：`F:/project/ledGame/.build/catalog-packaged-U8KfUP/results.json`。
- 原 `release/win-unpacked` 和 `release/LED Game-0.1.0-win.zip` 已覆盖更新。EXE、app.asar、后端 JAR、conf.json、wiring.json 五项 SHA-256 一致。
- OpenSpec 严格校验与 `git diff --check` 通过。本次不访问实际人工游戏/会员数据库，不连接真实硬件；未提交或归档。

旧版符号灯仅做源码分析，结果见 `docs/旧版符号灯运行逻辑分析.md`。未向新版本移植外围灯倒计时、点击或生命业务。
