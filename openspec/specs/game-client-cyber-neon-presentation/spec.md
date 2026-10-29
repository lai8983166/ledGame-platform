# game-client-cyber-neon-presentation Specification

## Purpose

为游戏端建立与客户提供的 Grid 设计交付及 Vue 组件库一致的经典赛博霓虹外观，在统一页面和控件视觉表现的同时，保护已验证的业务操作、硬件交互、图片媒体资源以及待机动画，使此次换肤能够独立验证和回滚。

## Requirements

### Requirement: Unified cyber neon appearance

游戏端 SHALL 参考 Grid 设计使用深紫黑背景、青色及品红强调、半透明面板、细边框、圆角和清楚的文字层级，覆盖主窗口导航、分类/游戏列表、媒体和精灵库、编辑器、配置、帮助、语言和 SDK 页面。应用内模态弹窗 SHALL 使用白底黑字及可读控件，导航 Tab 的普通、悬浮和选中态 SHALL 保持白字。

#### Scenario: Consistent management pages
- **WHEN** 用户切换上述页面或打开现有弹窗
- **THEN** 管理页面采用一致的赛博霓虹视觉规则，模态弹窗白底黑字，现行功能入口可见并可操作

#### Scenario: Main background matches updated full-page reference
- **WHEN** 主窗口显示分类首页、列表或其他管理页面
- **THEN** 基础底色为#0a0118，按game清单/style-a-neon.html原参数叠加顶部紫色18%、左下青色10%、右下品红12%的径向渐变，保留淡扫描线及60px网格和径向遮罩；装饰在内容后面且不拦截点击，不附加到Touch、DebugPanel和副屏

### Requirement: Preserve business behavior

主题样式 MUST 与数据来源、字段、事件语义、校验规则、保存行为、窗口职责及业务操作隔离，不得因换肤改变启动、刷卡、触屏、排队、计时、积分、结算、SDK 或通信逻辑。

#### Scenario: Editing and saving remain equivalent
- **WHEN** 用户在任一玩法编辑器修改相同配置并保存
- **THEN** 主题样式不改变保存的数据和调用链，取消、撤销、确认和快捷操作按各自业务规则工作

#### Scenario: Runtime interaction remains equivalent
- **WHEN** 用户使用现有启动方式进行游戏，或通过 DebugPanel 操作
- **THEN** 流程顺序、响应事件、倒计时、实时数据显示和结算行为不因换肤发生变化

### Requirement: Preserve resources and standby presentation

游戏端 MUST 保留现有应用图标、封面、精灵、静态图片、音视频、自定义背景、待机画面及待机文字/动画，不替换为参考项目的示例素材，不改变资源引用和同步行为。

#### Scenario: Standby remains unchanged
- **WHEN** Touch 或副屏进入现有待机状态
- **THEN** 显示原有配置的待机画面与标题动画，不附加改变其外观的全局滤镜、主题背景或装饰覆盖层

#### Scenario: Configured assets remain intact
- **WHEN** 用户查看已有游戏封面、精灵、媒体预览或自定义副屏背景
- **THEN** 显示原有资源和内容，不引入参考项目的占位图片或演示数据

### Requirement: Preserve semantic matrix rendering

RGB、精灵及布线预览 MUST 保持现有颜色含义、坐标、矩阵尺寸、正方形格子与交互命中规则，主题色仅应用于外围控件和面板。

#### Scenario: Switching tools keeps matrix stable
- **WHEN** 用户切换新增对象、选择移动、四色画笔、精灵画笔或侧栏内容
- **THEN** 矩阵不因控件选中态变化而位移、拉伸、截断或产生额外滚动条，像素内容保持原有语义

### Requirement: Usable controls and overlays

控件 SHALL 明确区分悬停、选中、焦点、禁用、加载和错误状态。弹窗和装饰 MUST 不阻挡输入、点击、键盘操作或关闭后的焦点恢复，文字及操作区不得因换肤被遮挡。

#### Scenario: Input after closing a dialog
- **WHEN** 用户关闭确认、资源选择或其他现有弹窗后点击输入框
- **THEN** 输入框可以获得光标并正常输入，控件位置不因选中样式发生跳动

#### Scenario: Supported window sizes
- **WHEN** 用户在 1280×720、1920×1080 或现有支持的高 DPI 条件下使用主要页面及独立窗口
- **THEN** 操作按钮、表单和弹窗可见且可达，矩阵不变成长方形，DebugPanel 的矩阵底行完整可见

### Requirement: Offline and lightweight presentation

新样式 MUST 在离线打包版正常加载，不依赖在线字体、CDN 或开发机设计目录，并不得为装饰效果改变实时游戏、矩阵刷新或得分更新链路。

#### Scenario: Offline packaged launch
- **WHEN** 用户在未安装两份设计项目且无互联网的机器启动打包游戏端
- **THEN** 新样式和字体回退正常显示，现有资源及运行功能不受影响
