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

### Requirement: 新建后首次打开全局配置

用户新建游戏进入对应编辑器后 SHALL 首先显示全局配置页面，覆盖娱乐挑战与排位赛创建入口；普通编辑已有游戏不自动打开。配置、布线及关卡编辑 SHALL 共用顶部保存，切页保留草稿，保存前不提前创建游戏记录。

#### Scenario: 新建与普通编辑
- **WHEN** 新建游戏进入编辑器，或从列表编辑已有游戏
- **THEN** 前者首次加载完成后进入配置页，后者直接进入关卡编辑页面
