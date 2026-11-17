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
