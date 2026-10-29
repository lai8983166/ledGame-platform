# simple-game-life-modes Specification

## Purpose

为 Simple、Normal、Difficult 娱乐挑战游戏提供明确的每关独立生命与整局共享生命选择，让编辑保存、调试和真实游戏采用一致的生命值规则，同时保留旧游戏与其他玩法的可用性。

## Requirements

### Requirement: Editable life scope
三个共用编辑器 SHALL 在全局配置中提供娱乐／闯关选择，保存重开保留模式和总生命。Rank 不显示此选择。

#### Scenario: Save challenge mode
- **WHEN** 用户选择闯关并填写 20 条生命后保存重开
- **THEN** 仍显示闯关及总生命 20

#### Scenario: Invalid life total
- **WHEN** 闯关总生命为零、负数或非整数
- **THEN** 阻止保存并显示可理解的提示

### Requirement: Exclusive life limits
娱乐模式 SHALL 每关重置该关生命；闯关模式 SHALL 只使用整局总生命，跨关不重置且耗尽整局失败，保留但忽略关卡生命设置。

#### Scenario: Challenge crosses levels
- **WHEN** 整局生命 20 在第一关扣除 2 条后进入下一关
- **THEN** 仍为 18 条，不受下一关生命限制影响

#### Scenario: Entertainment crosses levels
- **WHEN** 第一关剩余 2 条，进入配置 10 条生命的下一关
- **THEN** 新关生命为 10

#### Scenario: Switch back
- **WHEN** 从闯关切回娱乐
- **THEN** 原关卡生命设置恢复生效

### Requirement: Compatible editing
系统 SHALL 复用已有生命值字段；未包含新字段的旧编辑请求不能重置原模式，新游戏默认娱乐。

#### Scenario: Partial legacy document
- **WHEN** 已启用闯关的旧游戏收到未带全局生命字段的编辑请求
- **THEN** 保存后原整局生命配置保持不变
