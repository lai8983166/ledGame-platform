## Purpose

为独立精灵库提供直观的缩略图列表，在同一页面识别真实图形与颜色并维护精灵，替代名称列表加固定大预览的浏览形式且不改变编辑器精灵选择行为。

## ADDED Requirements

### Requirement: Accurate selectable spirit thumbnails
The independent spirit library SHALL show selectable thumbnails with the actual spirit color and aspect ratio, together with identifying name and dimensions. It MUST retain creation, editing and confirmed deletion without altering the editor's separate spirit browser.

#### Scenario: Browse different dimensions and colors
- **WHEN** spirits with different dimensions and colors are loaded
- **THEN** their thumbnails fit without cropping or aspect-ratio distortion and use their stored colors

#### Scenario: Maintain selected spirit
- **WHEN** a user selects a thumbnail and edits or deletes that spirit
- **THEN** the existing maintenance actions operate on that selected spirit and the thumbnail list refreshes
- **AND** canceled deletion leaves the spirit intact
