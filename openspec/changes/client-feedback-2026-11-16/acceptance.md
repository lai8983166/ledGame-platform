# 2026年11月16日输入反馈验收记录

## 实现范围

游戏端 `F:/project/ledGame` 完成八个原生确认入口迁移、异步目标有效性校验、名称输入焦点、模态快捷键隔离、组合输入保护及原生窗口异步焦点所有权修复。后端业务源码、授权算法和数据结构未修改。规划与规格保存在本 change，完成后继续保持活跃。

## 已完成的验证

- `npm test`：557项全部通过，包含国际化检查；新增确认控制器、过期目标、组合输入、模态键盘和窗口所有权行为测试。旧焦点测试已改为验证不失焦、不抢窗口，而非固定两次 blur。
- `pnpm build`：构建通过。
- `pnpm test:dialog-input`：Chrome真实组件渲染通过七类交互回归。
- `pnpm test:dialog-input:electron`：Electron 34.5.8 在 Windows 上运行同一套真实组件键盘交互，通过七类回归，退出码0、页面错误0。最终证据为游戏端 `.build/dialog-input-sMXbpN/result.json`。
- 独立模态回归：效果及像素灯弹窗在容器和按钮焦点下共48次按键，文档、对象、帧、选择、工具和撤销/重做状态保持不变，证据为 `.build/modal-input-browser-result.json`。

七类交互包括：销毁确认组件的迟到回复不能结算新请求；连续三次确认删除后立即新增并用键盘输入；组合输入标志及229的Escape保留草稿；取消删除无写入；效果/像素灯弹窗隔离底层快捷键；Escape只取消顶层影响确认；效果应用仍只有一个撤销事务且旧确认不能写入新文档。

测试仅使用临时内存API、测试文档和隔离用户目录，不读取厂家私钥、已有授权或客户数据库。

## 验证边界

Windows Electron 自动化使用真实渲染控件的点击与键盘事件，不以直接设置 input 值证明焦点正常。中文文本提交与模拟组合输入标志已通过；真实输入法候选窗口仍需现场复核。

额外尝试了原生 Windows 鼠标键盘复核，但 computer-use 截图返回 `FrameArrived timed out`，读取控件后点击又返回 `coordinate input geometry is unavailable`。未取得可验证的原生输入结果，因此这部分不计通过，不用自动化结果替代该结论。

## 发行包

标准资源准备和 renderer 构建已完成。当前 Windows 环境对打包器输出目录重命名返回 EPERM，最终采用本地同版本 `node_modules/electron/dist` 复制打包，继续执行原 beforePack、五个 fuse 和完整性资源写入。交付原 `release/win-unpacked` 与 `release/LED Game-0.1.0-win.zip`。

2026年11月17日最终验收结果：

- `portable:verify-protection` 通过：五个 fuse、EXE 内嵌 ASAR header、包内 manifest 和外置 JAR 一致。
- `portable:smoke-protection` 四类全部通过：注入环境下正常启动/机器码/未激活门禁/回环监听；修改 JAR 在准备数据库与启动 Java 前拒绝；修改 ASAR 拒绝；`resources/app` 替换拒绝。证据为 `.build/protection-smoke-NHfPVE/result.json`。
- 逐字节核对 ASAR 内19个 renderer 文件与当前 `dist/` 一致。
- ZIP 大小：933829955字节。
- ZIP SHA-256：`a0ae0985f82f3a4a608700ae12a5277b4baff5a8d2b5a0b3fd2172fbe8f378ae`。

## 提交记录

游戏端实现提交 `035fc57` 已 push 至 `lai8983166/ledgame` 的 `main`。规划与验收提交 `feac099` 已 push 至 `lai8983166/ledGame-platform` 的 `main`，本次后续提交回写全部任务完成状态；change 保持活跃，不自动归档。

## 11月17日追加：每份包独立数据与配置资源

正式包在单实例锁前选择 EXE 同级 `data`，数据库、配置、语言、备份、branding、授权和会话由该根派生；开发及显式隔离目录保留。不可写时明确提示并退出。配置资源经原有安装流程复制到 `data/branding`，JSON 存相对路径，运行时只解析当前包资源，旧受管绝对路径重定位。新包使用随包种子和默认配置；旧共享数据保留，迁移及升级步骤见游戏端 `docs/portable-data.md` 与随包软件介绍。

本轮验证：

- `npm test`：567项全部通过（含国际化）。新增10项覆盖目录、启动失败、锁前初始化、三类资源复制、预览与保存、相对路径、旧路径重定位、越界及复制/移动后的独立性。
- `portable:prepare`、`pnpm build` 及目录/ZIP打包成功。继续使用本地同版本 Electron 分发文件复制打包，并保留原始 beforePack 和五个 fuse。
- `pnpm portable:smoke-data`：实际发行包 A/B 同时启动，从无关 cwd 启动各自 EXE，后端日志确认 Hikari 连接到各自 `data/database/runtime/ledgame`，独立端口，同包二开退出；分别写入 H2 标记后两轮重启仍保留不同值，B 修改不影响 A；停机复制 A 至 C 后 C 资源归属当前目录，C 配置与数据库修改不影响 A，C 再次重启保留。证据为 `.build/portable-data-smoke-1PDJq8/result.json` 及 `database-queries.log`。
- 配置读取使用发行 ASAR 中的实际设置存储模块，检查主进程 resourceRoot 接入及目录配置早于单实例锁。未通过已激活 UI IPC 做配置操作；没有复制真实授权或添加绕过。两包均保持未激活业务403门禁，真实硬件连接禁用。
- `portable:verify-protection`、`portable:smoke-protection` 均通过，后者证据为 `.build/protection-smoke-Wgndzs/result.json`，原四类保护未退化。
- 独立逐字节核对发行 ASAR 六个相关运行模块与源文件相同；ZIP共978项，无 `data`、测试脚本或验收数据。证据 `.build/portable-data-artifact-verification.json`。

本轮 ZIP 替代上节历史输入修复包，仍位于 `release/LED Game-0.1.0-win.zip`：933832615字节，SHA-256 `e9e53bcfc328ab17f9fe6c63e1be331aedf918884087dd177a589dab0d77826e`。目录版为 `release/win-unpacked`。后端业务代码、数据库结构及授权签名算法均未修改。

本轮游戏端提交 `5898157` 已 push 到 `lai8983166/ledgame` 的 main。规划同步记录追加需求及验收，全部15项任务完成，change 继续保持活跃。

## 后续验收：重新打包继承和本机授权

按用户明确要求，正式三个打包入口统一使用 `scripts/portable-rebuild.cjs`。先预检停机、快照旧发行 `data`/媒体/控制器并记录摘要，在独立暂存目录完成构建与保护/保留校验，再发布程序和新 ZIP；原目录 data 原位保留。新 ZIP 带入数据库及备份、应用配置/语言、branding、游戏媒体及控制器设置，排除授权/日志/会话。ZIP通过同目录临时文件复制完成再替换，构建失败或来源变动拒绝发布；快照及暂存文件保留。

授权改为固定 `%APPDATA%/led-game/activation`（同机同 Windows 用户），各包仍独立数据库/配置。旧包和历史 Java 默认授权候选先验签、验产品及真实机器身份，再用文件锁和原子替换迁移；有效固定授权优先，保存失败不报激活，旧文件保留。显式 LED_USER_DATA_DIR 仍完整隔离，不读取正式授权。不改变机器码、激活码格式、公钥和业务门禁。

验证结果：

- 前端 `npm test`：587项全部通过，含国际化、授权路径及15项重打包真实文件系统测试；后端全量 `mvn test`：776项通过，最终11项授权核心用例复核通过。
- 首次暂存构建发现 builder JS 对象配置与磁盘配置再次合并，导致媒体重复复制 EBUSY。原目录681项摘要确认全部不变。改用唯一生成配置文件及独立 Node CLI 子进程；新增真实配置加载和失败退出传播验证。失败工作目录 `.build/portable-rebuild-VdUGHX` 保留。
- 最终 `pnpm portable:dist` 成功。快照/暂存/摘要/结果：游戏端 `.build/portable-rebuild-yRHqgC`。该入口继续执行原 beforePack、五个 fuse、ASAR/JAR 完整性校验；不会清理原发行 data。
- 独立对比打包前原目录、新目录版及新ZIP：681项持久文件、826307350字节全部 SHA-256 一致。4个相关运行模块在ASAR中与源文件逐字节一致；ZIP无 data/activation、logs 或 session。证据 `.build/rebuild-preservation-artifact-result.json`，打包前清单 `.build/rebuild-user-state-before.json`。
- `portable:smoke-data`：A/B/C实际发行副本使用各自H2及配置，重启后保留独立SQL标记；不携带包内授权，真实本机授权结果均 `machineActivated:true`，业务API200；复制后的独立资源路径、修改不影响原包均通过。证据 `.build/portable-data-smoke-Bi0Iwo/result.json`。
- 实际完整解压最终ZIP到新目录，验证完整性后从无关cwd启动；包内无授权文件，本机自动激活且业务API200，所继承的设置文件启动前后不变。真实硬件及会员房间连接禁用，未修改原发行数据库。证据 `.build/zip-activation-smoke-sx9Zh7/result.json`。
- `portable:smoke-protection`：显式隔离授权仍未激活、业务403；环境注入、JAR/ASAR篡改及解包替换四类验收全部通过。证据 `.build/protection-smoke-ywEhyZ/result.json`。

最终交付仍为 `release/win-unpacked` 和 `release/LED Game-0.1.0-win.zip`。ZIP大小934986977字节，SHA-256 `a4a68876eaf095bc834aa859288b35acfea9806f4fe5799aa34bc543a7bcfdff`，替代前述历史包。当前机器原有效授权未进入发行ZIP、Git或日志；本轮真实测试无需私钥或重新签发授权。

本轮游戏端提交 `5a7a429`、后端提交 `6be245c` 均已 push 至各自 main。当前共19项任务全部完成，规划与验收同步提交，change 保持活跃、不自动归档。

## 后续验收：游戏首页返回分类根页

问题来自分类详情沿用父层 home section，相同值重复赋值不触发清除分类；KeepAlive继续保留详情。成功通过编辑离开保护后的显式游戏导航现在增加请求序号，目录同时监听 section/请求，退出分类详情和选择器。分类点击仅改变内部选择；从编辑器正常返回不发导航请求，仍保留原分类。

- 对 `5a7a429` 中真实 App/GameListView 运行同一浏览器用例，进入分类并点击游戏/首页后等待分类卡片失败，复现原问题，页面错误0。证据 `.build/game-home-navigation-A7XbVk/result.json`，不是最初开发服务器加载超时的那次尝试。
- `pnpm test:game-home-navigation` 最终五组真实组件交互通过：连续进入A/B/A分类后首页返回；Escape/外部点击关闭菜单保留分类；普通编辑返回保留分类；取消未保存离开不改变页面，确认首页才重置；其他Tab与游戏列表返回首页。页面错误0，证据 `.build/game-home-navigation-SJC8AB/result.json`。
- `npm test`：587项全部通过（含国际化）。业务数据库、后端代码及授权路径未修改。
- `pnpm portable:dist` 通过，快照及暂存位于 `.build/portable-rebuild-BJeRrh`。独立核对原发行完整运行数据摘要不变，ZIP内683个持久文件匹配打包前当前数据，19个renderer文件与新dist逐字节一致，ZIP不含授权/日志/会话。证据 `.build/game-home-package-verification.json`。
- `portable:smoke-protection` 四类验收全部通过，证据 `.build/protection-smoke-TzOeHD/result.json`。

最新 ZIP `release/LED Game-0.1.0-win.zip` 大小946441493字节，SHA-256 `699707c838841fefac141e994b15eef3cbfcd5a5e76122a3ffdced2de8233de4`。游戏端提交 `8935e98` 已 push 到 main，全部21项任务完成；规划仓库同步提交，change保持活跃。

## 11月18日：全局配置板块样式微调

按反馈保留原有字段、板块顺序和列布局，在共用 GameGlobalConfigDialog 的板块上增加14px/16px内边距、1px细边框、10px圆角和主题 surface 底色；保留18px分区间距。普通编辑和排行编辑共用此样式，深色编辑页略深、浅色弹窗使用浅灰分区，无新业务规格或配置字段。

`npm test` 的587项全部通过。使用真实组件在普通编辑、排行编辑、浅色弹窗及1024×768较窄视口检查，板块非透明、边框1px、间距18px、无横向溢出且页面错误0；已目视复核深色页面与浅色弹窗截图。证据与截图位于游戏端 `.build/global-config-panels-Nhk7cz`。此次未添加低影响样式的实现镜像测试，后端代码未修改。

`pnpm portable:dist` 成功，快照及构建位于 `.build/portable-rebuild-OsuG8w`。独立核对原运行数据摘要不变、新ZIP保留683个持久文件、19个renderer文件与新dist逐字节一致；ZIP无授权/日志/会话。证据 `.build/global-config-panels-package-verification.json`。最新ZIP为946459007字节，SHA-256 `669bf5f0f1cef167c5774fb07a07ed92ec048d6100adb496f5cbae7fe5356f77`，仍交付 `release/LED Game-0.1.0-win.zip` 与 `release/win-unpacked`。游戏端提交 `b786e98` 已push至main。

## 11月18日 Windows 双入口发行

`LED Game.exe` 使用用户提供的彩色方块图标，打开原有管理界面。`LED Game Play.exe` 使用霓虹游戏人物图标，授权及初始化完成后直达现有全屏「开始游戏」界面。两份图标各含 16–256px 九种尺寸。两个 EXE 位于同一个目录，共用该目录的数据、配置和单实例锁。全屏退出沿用包内退出密码，成功后返回管理界面。

- `pnpm test` 的 594 项全部通过。Chrome 真实渲染补充覆盖授权成功后继续进入、运行中编辑草稿离开选择以及启动失败显示。证据位于游戏端 `.build/dual-launch-renderer-rxBuJu/result.json`。
- 实际 Windows 发行版使用本机授权启动。验证管理入口、快捷入口唤起同包全屏界面、重复打开仍只有一个后台和一个游戏窗口、快捷入口冷启动只显示全屏窗口、退出密码与管理入口唤回，以及隔离授权后显示激活窗。证据及截图位于 `.build/dual-exe-ui-Xu8dQz`。
- `pnpm portable:dist` 成功，快照位于 `.build/portable-rebuild-5VRNrX`。两份 PE 的图标、五项 Electron fuse 和 ASAR 完整性分别通过检查，ZIP 内两个 EXE 的摘要与验证过的文件一致。独立目录构建也生成并验证两个 EXE，证据位于 `.build/dual-exe-dir-only-WxxBtg/result.json`。
- 快捷入口通过原有保护验收的四组检查，证据位于 `.build/protection-smoke-x6SMA5/result.json`。原运行数据摘要不变，ZIP 保留 683 个持久文件，19 个渲染文件匹配构建产物，授权、日志和会话文件未带入 ZIP。证据位于 `.build/dual-exe-package-verification.json`。

游戏端提交为 `3474593`。本轮交付 `release/win-unpacked` 和 `release/LED Game-0.1.0-win.zip`。ZIP 大小 1027361664 字节，SHA-256 为 `619181839b46628fee3f3029aa91fb75b6008d95d50b4474f1a87758a741dc2a`。

## 11月18日 逐游戏控制器通道数

逐游戏布线增加沿用包配置和 1、2、4、8 路选择，线路列表显示其分控及端口。通过 `wiringData.controlChannel` 保存和迁移，缺省继续依包内型号分组。运行时按游戏选择覆盖型号，搜索、发帧与踩踏解析使用同一配置。编辑器打开调试时按该游戏的型号探测，保持待机映射。退出恢复包内配置。

- 前端 596 项与后端 796 项测试通过。后端使用旧版搜索报文和模拟 UDP 测试 HC01/HC02，并验证普通与排行数据持久化、全局恢复和独立调试探测。本轮未接真实分控。
- 真正的 Windows 发行程序使用隔离的包目录和本机有效授权。普通游戏选 2 路，排行游戏选 1 路，通过原有保存入口落入 H2，导出保留通道数，重启后各自读回且线路空槽完整。包内硬件配置摘要不变。证据在游戏端 `.build/game-controller-package-Wf0STF/result.json`。
- `pnpm portable:dist` 已生成目录和 ZIP，快照在 `.build/portable-rebuild-dRJytR`。原目录数据摘要不变，ZIP 内 683 个持久文件匹配打包前数据，两个 EXE、其图标和完整性通过校验，19 个渲染文件匹配构建。验收记录在 `.build/game-controller-package-verification.json`，保护冒烟记录在 `.build/protection-smoke-iheooF/result.json`。

游戏端 `8667513`、后端 `729e1de` 已推送到各自的 main。当前 ZIP 为 1027367690 字节，SHA-256 为 `110b39a9124f28d0a1ca10c636291279467ca01dc3a81dfa167da31f43c8edbe`。

## 11月18日 窗口、副屏、分类与编辑器

- 管理窗口标题栏仅显示标题。配置页可实时预览三种副屏下标题样式，上下标题间距从 3vh 调为 4.5vh。
- 普通游戏和排行游戏保存成功时在顶部提示 3 秒，连续保存重新计时。布线矩阵四边预留 12px，在三种窗口尺寸下验证缩放、末格点击和框选。
- 发行包实测导出带分类的游戏、导入后生成新分类卡片并查看所属游戏。普通和排行编辑器能保存独立布线，重启后读取成功。记录位于游戏端 `.build/game-controller-package-Dauvk2/result.json`。
- 游戏端 597 项、后端 797 项测试通过。新版 ZIP 含两个 EXE，逐项核对 683 个持久文件，打包前后原目录数据摘要一致。记录位于游戏端 `.build/feedback-nov18-package.json`。
- 游戏端提交 `041353c`，后端提交 `947d9da`。发行包 SHA-256 为 `1f8188179435136261f43194108b1dc5afc79205e951b1218c2bc9debdb7b1af`。

## 11月22日 娱乐挑战、RGB 编辑与连续调试

- 游戏端 597 项测试、后端 801 项测试通过。Chromium 验证了编辑器 10 个交互场景、调试页 18 个场景和 36 组矩阵尺寸、窗口尺寸及显示缩放组合。点击 1 CSS 像素格缝不会触发相邻格子。
- 发行版通过模拟踩踏逐格消除两格蓝色对象、将两格紫色对象分别转蓝并消除，确认得分和 double 音效均已启动；调试页从生命值 2 的第一关自动进入生命值 4 的第二关。末关结束后，系统回到 IDLE，输出帧与待机 GIF 像素匹配。证据位于游戏端 .build/editor-debug-packaged-kVXDD6。本轮未连接实体地砖。
- 红格命中后在原位交替黄闪五个 tick，即使最后一击耗尽生命也会先播完再结算。后端测试覆盖持续踩踏、移动对象、提前停止及本关失败推进。
- 原发行目录中的数据、媒体和控制器配置已保留。快照位于游戏端 .build/portable-rebuild-ohbC8E。ZIP 中 683 个持久文件与原快照一致，两个 EXE、渲染资源和后端 JAR 与目录版一致。ZIP 的 SHA-256 为 ead763bb92b85c3643e75930b951f557cea81ad59d109baf6b65df59cdd77c12。
- 游戏端 980e34d、后端 9c1bd23 已推送到各自的 main。

## 11月22日 五次受伤闪烁与手动结束调试

- 逐 tick 验证了命中原位置上的五次黄色提示，每次亮五个 tick，四段间隔各五个 tick。移动红格的间隔显示实时底色；致命踩踏完整播放 45 个 tick，第 46 个 tick 才可结算。
- Windows 发行版实测“启动游戏”切换为“结束游戏”，点击后回到系统 IDLE 并恢复待机 GIF 和音乐，可在同一调试页再次开局。发行版使用模拟地砖输入。验收记录位于游戏端 .build/editor-debug-packaged-vWFhLe。
- 前端 597 项、后端 802 项测试通过。Chromium 调试界面 18 个场景通过，覆盖结束失败重试以及启动中、结算中、暂停状态。
- Windows 目录版和 ZIP 已更新并保留数据、媒体和控制器配置。快照为 .build/portable-rebuild-blnJa3，ZIP 的 SHA-256 为 1bd3ee18687e358499ae13981489c8751ab0517fcac8ae7e79a20de373805d4c。游戏端 173b34f、后端 65fe06c 已推送至 main。

## 2026-11-23 调试、玩法介绍和人数筛选

- 前端 598 项测试及后端 806 项测试通过。正式入口覆盖仅有下限、仅有上限、双侧限制、无限制以及无可选游戏时禁用下一步。
- Windows 发行程序验证了调试标题、返回按钮、Rank 视频保存与预览、人数配置读回和副屏在开场语音期间实际播放 MP4。两关连续调试和手动结束均成功恢复待机 GIF。
- Windows 音频测试中，缩短背景 MP3 缓冲后，爆炸开始时已排队的原音量背景音乐由约 377 毫秒降至 67 毫秒。未在场馆音响上试听。
- 游戏端提交 d56e5a7，后端提交 abc804e，发行包实测记录位于 editor-debug-packaged-77LXTl。

## 2026-11-24 Rank 炸弹 3×3 得分块

- 前端 599 项、后端 808 项测试通过。Windows Chromium 编辑器 10 个场景通过，包括超上限即时提示、缩小坐标范围后重新校验、容量边界保存与回读。
- 运行时验证 8 格区域填 8 时，3 人各获得 2 格，另 2 格可容纳炸弹；1 人获得全部 8 格。总数小于人数时每人获得 0 格。
- 历史关卡首次在新版编辑器保存时，以原每人数量乘以配置的最多人数换算上下限；最多人数留空按 6 人，换算值以所选区域容量为限。

## 2026-11-24 Simple 预览与 Rank 爆炸音量

- 前端 599 项和后端 810 项测试通过。Chromium 验证了 1024×600 及 1920×1080 下的预览尺寸、关卡滑动条独立配置和保存回读。
- 使用发行素材 Ranking/bomb.mp3 验证数字输出，150% 相对原声的均方根电平增加 3.52 dB，200% 增加 6.02 dB。测量位于扬声器输出前。
- Windows 发行程序从隔离数据库读取 125% 的关卡配置，滑动条调至 200% 后成功保存回读。验收记录位于 editor-debug-packaged-LI3s9g。

## 2026-11-24 调试按钮与更多人数

- 前端 599 项和后端 811 项测试通过。Chromium 调试页 18 项场景覆盖开场、暂停、结束及再次开局；正式入口验证取消恢复焦点、8 人触屏开局、7 人手环逐人准入。
- Windows 发行版验证两个调试按钮在待机和开局后互斥，手动结束恢复待机 GIF；在正式入口点击“更多”选择 8 人、进入可玩游戏、选择关卡并以 8 人提交开局。隔离数据记录位于 editor-debug-packaged-lkwkba。
