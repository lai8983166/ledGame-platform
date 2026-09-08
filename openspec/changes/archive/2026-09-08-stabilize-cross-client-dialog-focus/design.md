## Context

参见 `proposal.md` 的 Why。平台两个 renderer 的问题来自两类边界：共享 modal/drawer 没有恢复 opener；自助注册端软键盘只监听 `focus` 且关闭时未 blur，使同一输入框无法再次触发事件。会员管理端另有两处直接 `window.confirm`，会触发 Electron 原生焦点丢失。

## Goals / Non-Goals

**Goals:**

- 用共享组件统一管理应用内弹层的 opener 记录、初始焦点与关闭恢复。
- 让软键盘的可见状态与真实 DOM 焦点保持一致。
- 让刷卡监听明确避开任何可编辑事件目标。
- 用自动化测试固定这些边界，并静态阻止会员管理端重新引入裸 `window.confirm`。

**Non-Goals:**

- 不改变注册、充值、回收业务规则。
- 不引入全局状态库或第三方 focus-trap 依赖。
- 不处理游戏端代码；游戏仓库使用同名联动 Change 独立实施。

## Decisions

### 1. 焦点生命周期下沉到共享弹层组件

`BaseModal` 和 `SideDrawer` 在挂载前保存 `document.activeElement`，打开后聚焦自己的容器；卸载后在下一次 DOM 更新中，仅当 opener 仍连接到文档时恢复焦点，否则 blur 遗留活动元素。这样所有现有调用者自动得到一致行为。

备选方案是在每个页面关闭回调中手动 `.focus()`；该方案容易漏掉取消、Escape 和异步成功等分支，因此不采用。

### 2. 会员管理端破坏性确认使用受控应用内 modal

手环清零和回收共享一个确认状态与执行入口。确认 modal 复用 `BaseModal`，避免经过 Chromium/Electron 的原生 `confirm` 边界。

备选方案是保留 `window.confirm` 并在 finally 中恢复焦点；它仍会短暂切走 WebContents，且平台端已有成熟的应用内 modal，因此不采用。

### 3. 软键盘关闭时主动 blur，并同时响应 focus 与 pointer/click

`closeKeyboard` 先 blur 当前活动的可编辑控件，再清空目标引用；输入控件除 `focus` 外还通过指针事件确保“仍然是 activeElement”时也能重新打开键盘。打开员工密码等输入弹层前先取消刷卡状态。

### 4. 刷卡监听按事件目标做最后一道隔离

全局 keydown 在处理手环 buffer 前判断 `input`、`textarea`、`select`、`contenteditable` 等可编辑目标。该判断独立于软键盘状态，避免状态不同步时吞键。

## Risks / Trade-offs

- [关闭弹层后 opener 已被列表刷新移除] → 只聚焦 `isConnected` 的元素，并允许页面自然选择下一个焦点。
- [pointer 与 focus 连续触发两次打开逻辑] → 打开函数保持幂等，只更新同一 activeInput 和布尔状态。
- [物理读卡器恰好在密码框聚焦时刷卡] → 优先保护人工输入；用户关闭密码弹层后重新刷卡。

## Migration Plan

无数据迁移。先补回归测试，再替换原生确认框和焦点处理；若出现回归，可逐文件回退，不影响后端数据。
