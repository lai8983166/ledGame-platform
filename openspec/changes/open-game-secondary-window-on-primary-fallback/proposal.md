# Proposal

## Why

游戏端启动时只有一块显示器会跳过副屏窗口，单机用户无法直接看到副屏内容。需要自动在主显示器打开副屏窗口，同时保留真实副屏的全屏展示。

## What Changes

- 没有外接显示器时，自动在主显示器工作区内打开可关闭的普通副屏窗口。
- 有外接显示器时继续优先使用已保存的选择，否则使用第一块外接显示器。
- 主屏回退不覆盖已保存的外接显示器设置；单屏时关闭副屏窗口后可再次打开。

## Capabilities

### New Capabilities

- `game-secondary-display-startup`: 游戏端副屏窗口的自动启动及单屏回退。

### Modified Capabilities

无。

## Impact

用户指定的游戏端 `F:/project/ledGame` 的 Electron 副屏窗口创建、启动逻辑与测试；重新生成其原位置的 win-unpacked 与 ZIP。不修改会员管理端、自助注册端或游戏业务逻辑。
