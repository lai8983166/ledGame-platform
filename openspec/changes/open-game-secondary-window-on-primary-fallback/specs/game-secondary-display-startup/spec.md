# Spec Delta

## Purpose

保证游戏端在单屏和多屏环境中启动时都能呈现副屏内容，并保持外接显示器优先、单屏窗口可操作以及已有显示器选择不被回退逻辑覆盖，支持客户无需额外硬件查看副屏。

## ADDED Requirements

### Requirement: Automatically open secondary content on an available display
游戏端启动时 SHALL 自动打开副屏窗口，优先使用已保存且在线的外接屏，否则使用首个外接屏，没有外接屏时使用主屏。

#### Scenario: Only the primary monitor is present
- **WHEN** 启动游戏端时仅检测到主屏
- **THEN** 主屏自动打开可移动、缩放、关闭的普通副屏窗口，其初始边界位于工作区内

#### Scenario: External monitor is available
- **WHEN** 启动时有外接显示器
- **THEN** 在已保存的有效外接屏或首个外接屏全屏显示副屏

### Requirement: Preserve display preference and allow reopening
主屏回退 SHALL 不覆盖已保存的外接屏偏好，并允许单屏用户关闭副屏窗口后从原有菜单重新打开。

#### Scenario: Previously selected external display is disconnected
- **WHEN** 已保存外接屏但启动时只有主屏
- **THEN** 副屏在主屏打开，保存的外接屏选择不变

#### Scenario: Reopen on the primary monitor
- **WHEN** 单屏用户关闭副屏后点击打开副屏
- **THEN** 主屏重新显示副屏窗口，不创建重复窗口

#### Scenario: Attach an external monitor after primary fallback
- **WHEN** 回退窗口在主屏打开后接入外接显示器
- **THEN** 副屏自动在外接屏全屏显示并关闭原回退窗口
