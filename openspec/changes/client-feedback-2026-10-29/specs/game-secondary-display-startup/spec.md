## RENAMED Requirements

- FROM: `### Requirement: Automatically open secondary content on an available display`
- TO: `### Requirement: Manually open secondary content fullscreen`

## MODIFIED Requirements

### Requirement: Manually open secondary content fullscreen
游戏端 SHALL 仅由用户手动打开副屏，启动及连接外接屏时均不自动创建副屏窗口。手动打开时 SHALL 优先使用已保存且在线的外接屏，否则使用首个外接屏，没有外接屏时使用主屏；所有情况 SHALL 全屏显示。

#### Scenario: Only the primary monitor is present
- **WHEN** 单屏或多屏环境启动应用，或之后接入外接屏
- **THEN** 不自动打开副屏，用户可从菜单手动打开

#### Scenario: External monitor is available
- **WHEN** 用户手动打开副屏
- **THEN** 在所选可用屏幕全屏展示，单屏也全屏，不创建普通回退小窗口

### Requirement: Preserve display preference and allow reopening
主屏回退 SHALL 不覆盖已保存的外接屏偏好，关闭副屏窗口后 SHALL 可以从菜单重新手动打开，不创建重复窗口。接入外接屏 SHALL 不自动打开或移动副屏窗口。

#### Scenario: Previously selected external display is disconnected
- **WHEN** 已保存外接屏但当前只有主屏，用户手动打开副屏
- **THEN** 主屏全屏显示，保存的外接屏选择不变

#### Scenario: Reopen on the primary monitor
- **WHEN** 手动重复打开副屏或接入外接显示器
- **THEN** 重复打开复用现有窗口，接屏仅更新可用显示器列表，不自动创建或迁移窗口

#### Scenario: Attach an external monitor after primary fallback
- **WHEN** 主屏已手动打开副屏后接入外接屏
- **THEN** 仅更新显示器列表，现有窗口不自动迁移或创建新窗口
