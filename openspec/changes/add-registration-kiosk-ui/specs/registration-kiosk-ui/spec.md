## ADDED Requirements

### Requirement: 横屏触控自助机界面
系统 SHALL 为 `registration-kiosk` 提供适合 16:9 横屏触控设备的自助机界面，并使用统一的蓝青色科技视觉层级、大尺寸触控目标和高对比文字。

#### Scenario: 在横屏自助机启动程序
- **WHEN** 程序启动且可视区域为常见横屏比例
- **THEN** 系统在单屏内完整展示当前步骤的标题、核心内容和主要操作
- **AND** 关键触控目标具有适合手指操作的尺寸与间距

#### Scenario: 减少动画偏好
- **WHEN** 设备启用了减少动态效果偏好
- **THEN** 系统减少或停止扫描、脉冲和页面转场动画
- **AND** 流程与状态反馈仍保持完整可理解

### Requirement: 自助机首页
系统 SHALL 在首页展示 `Self-Service` 品牌标题、`Activate Wristband` 主入口和参考图中的 `Player Info Query` 次要入口，并仅为主入口提供本 change 的完整后续流程。

#### Scenario: 进入手环激活流程
- **WHEN** 用户在首页点击 `Activate Wristband`
- **THEN** 系统进入手机号确认界面

#### Scenario: 首页初始状态
- **WHEN** 程序首次启动或上一位用户点击 `Return Home`
- **THEN** 系统展示不包含上一位用户资料的首页初始状态

### Requirement: 手机号确认
系统 SHALL 提供电话号码输入框、返回和确认操作，并 SHALL 在电话输入框获得焦点时显示屏幕数字键盘。

#### Scenario: 聚焦电话输入框
- **WHEN** 用户点击电话输入框
- **THEN** 系统突出显示当前输入框并从屏幕底部显示数字键盘
- **AND** 输入框与确认操作不会被键盘遮挡

#### Scenario: 使用数字键盘输入电话
- **WHEN** 用户点击数字、退格或清空按键
- **THEN** 系统将相应变化立即反映到电话号码输入框

#### Scenario: 确认合法电话号码
- **WHEN** 用户输入符合界面规定长度和格式的电话号码并点击确认
- **THEN** 系统进入注册资料界面
- **AND** Phone Number 字段预填该电话号码

#### Scenario: 确认无效电话号码
- **WHEN** 用户在电话号码为空或格式不合法时点击确认
- **THEN** 系统停留在当前界面并在输入区域附近显示可理解的错误提示

### Requirement: 屏幕软键盘
系统 SHALL 为所有文本或数字输入字段提供可触控的屏幕键盘，电话号码和生日使用数字布局，姓名使用字母布局，并保留物理键盘输入能力。

#### Scenario: 切换输入字段
- **WHEN** 用户从一个输入字段切换到另一个不同类型的字段
- **THEN** 系统将软键盘布局切换为与新字段匹配的数字或字母布局
- **AND** 新字段获得明确焦点状态

#### Scenario: 完成软键盘输入
- **WHEN** 用户点击软键盘的 `Done`
- **THEN** 系统关闭键盘并保留已输入内容

#### Scenario: 关闭键盘
- **WHEN** 用户点击键盘关闭操作或当前页面返回操作
- **THEN** 系统关闭键盘且不丢失已确认的当前步骤资料

### Requirement: 注册资料表单
系统 SHALL 在注册资料界面展示 Avatar、Name、Phone Number、Birthday 和 Gender 字段，以及 `Back` 与 `Next Step` 操作。

#### Scenario: 查看预填注册表单
- **WHEN** 用户从手机号确认界面进入注册资料界面
- **THEN** 系统保留并显示已确认的 Phone Number
- **AND** 系统展示头像、姓名、生日和性别输入区域

#### Scenario: 聚焦姓名或生日字段
- **WHEN** 用户点击姓名或生日字段
- **THEN** 系统显示匹配字段类型的软键盘并确保该字段可见

#### Scenario: 选择性别
- **WHEN** 用户点击一个 Gender 选项
- **THEN** 系统突出显示唯一选中的性别选项

#### Scenario: 提交完整资料
- **WHEN** 用户已选择头像并填写所有必填资料且格式合法，然后点击 `Next Step`
- **THEN** 系统进入 `Swipe Wristband` 界面

#### Scenario: 提交不完整资料
- **WHEN** 用户缺少必填资料或字段格式不合法时点击 `Next Step`
- **THEN** 系统停留在注册资料界面
- **AND** 系统标识全部相关错误并将首个错误带入视线或焦点

### Requirement: 头像来源选择
系统 SHALL 在用户点击 Avatar 旁的编辑按钮后展示头像来源面板，并提供 `Set from Library`、`Take Photo` 和取消操作。

#### Scenario: 打开头像来源面板
- **WHEN** 用户点击 Avatar 旁的编辑按钮
- **THEN** 系统在注册资料之上显示头像来源选择面板

#### Scenario: 进入内置头像库
- **WHEN** 用户点击 `Set from Library`
- **THEN** 系统进入内置头像选择界面

#### Scenario: 点击拍照入口
- **WHEN** 用户点击 `Take Photo`
- **THEN** 系统显示相机未在 UI 演示中接入的明确反馈
- **AND** 系统不请求相机权限或调用真实摄像头

### Requirement: 内置头像库
系统 SHALL 在 `Select an Avatar` 界面以可滚动的触控网格展示本地预置头像，并提供选择、确认与取消操作。

#### Scenario: 选择内置头像
- **WHEN** 用户点击头像网格中的一个头像
- **THEN** 系统使用边框、勾选标识或等效方式突出唯一选中的头像

#### Scenario: 确认头像
- **WHEN** 用户选择一个头像并点击 `Confirm Avatar`
- **THEN** 系统返回注册资料界面并在 Avatar 区域显示该头像

#### Scenario: 取消头像选择
- **WHEN** 用户点击 `Cancel`
- **THEN** 系统返回注册资料界面且不改变打开头像库前的头像

### Requirement: 模拟刷手环界面
系统 SHALL 在 `Swipe Wristband` 界面展示玩家名称和头像、手环感应区域、等待状态、返回操作和明确标记为演示用途的模拟成功操作。

#### Scenario: 等待手环
- **WHEN** 用户进入刷手环界面且尚未触发模拟成功
- **THEN** 系统显示 `Waiting for wristband` 或等效等待状态及感应提示动画
- **AND** 系统不声称已经连接真实读卡器

#### Scenario: 模拟刷卡成功
- **WHEN** 用户点击 `Demo · Simulate Successful Swipe`
- **THEN** 系统显示手环已识别的短暂状态反馈
- **AND** 系统随后进入激活成功界面
- **AND** 系统不访问真实读卡器、IC 卡或硬件接口

#### Scenario: 从刷卡页返回
- **WHEN** 用户在等待状态点击 `Back`
- **THEN** 系统返回注册资料界面并保留当前会话资料

### Requirement: 激活成功界面
系统 SHALL 在成功界面展示 `Activation Successful`、成功状态图形、玩家资料摘要和 `60 min` 有效时长，并提供 `Return Home` 操作。

#### Scenario: 查看激活结果
- **WHEN** 模拟刷卡成功流程结束
- **THEN** 系统显示所选头像、玩家名称、成功状态和 60 分钟有效时长
- **AND** 页面明确说明当前结果为 UI 模拟

#### Scenario: 返回首页
- **WHEN** 用户点击 `Return Home`
- **THEN** 系统清空电话号码、注册资料、头像选择、错误和临时刷卡状态
- **AND** 系统返回自助机首页

### Requirement: 仅 UI 的会话边界
系统 MUST 使用前端内存状态和本地素材完成本 change 的全部流程，且 MUST NOT 执行真实会员查询、资料持久化、相机访问、读卡器通信或 IC 卡写入。

#### Scenario: 刷新程序
- **WHEN** 用户在任意流程步骤刷新程序
- **THEN** 系统可以恢复到不包含此前输入资料的首页初始状态

#### Scenario: 执行模拟外部能力
- **WHEN** 用户操作相机入口或模拟刷手环入口
- **THEN** 系统只显示明确的 UI 模拟反馈
- **AND** 系统不发送网络请求或访问设备硬件
