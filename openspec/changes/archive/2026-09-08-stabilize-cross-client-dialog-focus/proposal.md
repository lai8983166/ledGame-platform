## Why

会员管理端和自助注册端在关闭确认框、提示层、软键盘或抽屉后，部分输入框会保留错误的 DOM 焦点，或者 Electron renderer 失去系统焦点，导致再次点击仍无法输入。该问题分散在多个页面，必须建立统一的弹层焦点生命周期并补充回归测试，避免逐点修补后再次出现。

## What Changes

- 为共享 `BaseModal`、`SideDrawer` 建立“打开时记录触发元素、关闭后恢复有效焦点”的统一行为。
- 将会员管理端手环清零和回收流程从原生 `window.confirm` 改为应用内确认弹层，避免 Electron 原生对话框夺走 renderer 焦点。
- 收紧自助注册端软键盘的打开、关闭与重复点击行为：关闭时释放当前输入框焦点，再次点击同一输入框时可以重新唤起软键盘。
- 保证自助注册端刷手环捕获不会吞掉软键盘或普通输入框中的按键。
- 为以上行为增加自动化回归测试，并加入禁止新增未封装原生确认框的约束。
- 不修改会员、手环或游戏业务 API，不在本 Change 中重构无关页面样式。

## Capabilities

### New Capabilities

- `renderer-focus-continuity`: 规定平台桌面 renderer 在应用内弹层、软键盘和输入捕获切换前后的焦点连续性。

### Modified Capabilities

无。

## Impact

- 影响 `apps/member-admin` 的通用弹层组件。
- 影响 `apps/member-admin` 的手环确认交互及相关测试。
- 影响 `apps/registration-kiosk` 的软键盘、刷卡监听和相关测试。
- 不改变后端、数据库结构、IPC 协议或安装包配置。
