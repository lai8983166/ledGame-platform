# game-creation Specification

## Purpose

让用户在游戏列表中从已有玩法对应的大类新建独立游戏并继续编辑，明确尚未实现的大类不可创建，避免修改种子数据或改变已有游戏行为。

## Requirements

### Requirement: Supported game family creation
The game list SHALL offer game creation with 娱乐挑战, 排位赛 and unavailable 多维竞技. Entertainment creation MUST use the existing Simple shared editor and ranked creation MUST use the Rank editor. Existing simple, normal and diffcult SHALL be classified as entertainment and rank as ranked.

#### Scenario: Open a draft for either supported family
- **WHEN** a user selects 娱乐挑战 or 排位赛 and confirms creation
- **THEN** an unsaved draft without a database ID opens in its corresponding editor and no game row or card is created
- **AND** existing games and their contents are unchanged

#### Scenario: First save and subsequent saves
- **WHEN** a user explicitly saves a valid new draft
- **THEN** an independent game is persisted and appears in the manageable catalog after reload
- **AND** subsequent saves update the same game rather than insert additional rows

#### Scenario: Leave without saving or save draft configuration
- **WHEN** a user exits an unsaved editor or saves its global, pixel-light or wiring dialog
- **THEN** no game row or card is created; configuration dialogs only modify the in-memory draft
- **AND** debugging is unavailable until the game has been explicitly saved

#### Scenario: Cancel or choose unsupported family
- **WHEN** a user cancels creation or requests 多维竞技
- **THEN** no game is created and the unsupported option is visibly unavailable

#### Scenario: Creation fails
- **WHEN** persistence fails
- **THEN** a visible error is shown, the draft remains editable for retry, and no nonexistent game card is displayed
