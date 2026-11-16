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
