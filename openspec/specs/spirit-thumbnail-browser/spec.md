# spirit-thumbnail-browser Specification

## Purpose

为独立精灵库提供直观的缩略图列表，在同一页面识别真实图形与颜色并维护精灵，替代名称列表加固定大预览的浏览形式且不改变编辑器精灵选择行为。

## Requirements

### Requirement: Accurate selectable spirit thumbnails
The independent spirit library SHALL show selectable thumbnails with the actual spirit color and aspect ratio, together with identifying name and dimensions. It MUST retain creation, editing and confirmed deletion without altering the editor's separate spirit browser.

#### Scenario: Browse different dimensions and colors
- **WHEN** spirits with different dimensions and colors are loaded
- **THEN** their thumbnails fit without cropping or aspect-ratio distortion and use their stored colors

#### Scenario: Maintain selected spirit
- **WHEN** a user selects a thumbnail and edits or deletes that spirit
- **THEN** the existing maintenance actions operate on that selected spirit and the thumbnail list refreshes
- **AND** canceled deletion leaves the spirit intact

### Requirement: 精灵卡片维护和自动刷新
精灵库 SHALL 去掉固定修改、删除和刷新按钮，任意精灵卡片在悬停或键盘焦点进入该卡片时于底部显示修改和删除。边框高亮与发光 SHALL 同样由悬停或键盘焦点触发，不依赖选中状态；移出且无焦点时恢复普通样式。操作 SHALL 绑定按钮所属卡片的精灵，不依赖当前选中项。修改弹窗 SHALL 支持名称编辑，沿用既有名称校验，更新接口省略name仍保留原名。新建、修改和删除成功后 SHALL 自动刷新列表，取消不改变数据，失败仍可重试。

#### Scenario: 修改精灵并刷新
- **WHEN** 未选中卡片直接悬停后修改名称和点阵且保存
- **THEN** 请求保存对应精灵，列表自动显示新名称与点阵，其他属性保留

#### Scenario: 删除与取消
- **WHEN** 在任意悬停卡片底部删除并确认，或取消修改或删除
- **THEN** 确认删除后列表刷新，取消不写入
