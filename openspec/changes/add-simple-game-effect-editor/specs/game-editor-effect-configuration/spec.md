## Purpose

为 `simple`、`normal` 和 `diffcult` 共用的游戏编辑器提供可视化特效配置、预览和帧草稿生成能力，让特效能够复用精灵库点阵并通过现有游戏保存流程持久化。

## ADDED Requirements

### Requirement: Shared effect editor entry

系统 SHALL 在 `simple`、`normal` 和 `diffcult` 共用编辑器中提供“特效”入口；点击后 SHALL 打开独立配置弹窗，并加载当前关卡、当前帧和当前场地尺寸。

#### Scenario: Open effect editor for each default game

- **WHEN** 用户在任意一个默认玩法的编辑器中点击“特效”按钮
- **THEN** 系统打开同一套特效弹窗，并显示对应当前关卡和当前帧的草稿

#### Scenario: Cancel keeps the game editor unchanged

- **WHEN** 用户修改特效参数后点击取消或关闭弹窗
- **THEN** 系统停止预览、关闭弹窗，且当前关卡的帧内容和外层未保存状态均保持不变

### Requirement: Effect configuration fields and validation

系统 SHALL 支持当前帧前插入和从当前帧开始合并两种模式、颜色、起点、终点、矩形宽高、步长及“使用精灵”开关。场地尺寸 SHALL 只读显示。坐标输入使用从 1 开始的界面坐标，生成帧时转换为从 0 开始的内部坐标。

系统 SHALL 拒绝以下配置并在对应控件附近显示可理解的错误：起点或终点超出场地、矩形宽高小于 1 或超出场地、步长不是有限正数、使用精灵但没有可用精灵、以及精灵点阵放置后超出场地。

#### Scenario: Invalid coordinates are rejected

- **WHEN** 用户输入超出当前场地范围的起点或终点并点击绘制或确认
- **THEN** 系统显示坐标错误，不生成或写入任何帧，弹窗保持打开

#### Scenario: Invalid step is rejected

- **WHEN** 用户输入 0、负数、非数字或无穷大的步长
- **THEN** 系统显示步长错误，不启动预览且不修改当前帧

#### Scenario: Equal start and end create a static effect

- **WHEN** 起点和终点相同且其他参数合法
- **THEN** 系统生成至少一个有效帧，不进行除零计算，并在预览中显示静止对象

### Requirement: Color-aware spirit selection

系统 SHALL 将特效颜色按现有颜色索引过滤精灵选项：绿色仅提供“安全块”，蓝色仅提供“得分块”，红色提供“陷阱块”、“陷阱块2*2”、“陷阱5*5”、“1”至“10”、“13”、“14”、“20”和“15”，紫色仅提供“Double”。列表中的名称 SHALL 与精灵库名称一致，并使用精灵库中的点阵尺寸和点位。

勾选“使用精灵”后 SHALL 显示精灵下拉框；未勾选时 SHALL 隐藏或禁用该下拉框。用户切换颜色后，若新颜色有可用选项，系统 SHALL 自动选中新颜色的第一个选项，并立即刷新预览。

#### Scenario: Switching color resets spirit selection

- **WHEN** 当前选择红色精灵后切换为蓝色
- **THEN** 下拉框仅显示蓝色允许的精灵，并自动选中“得分块”

#### Scenario: Sprite mode requires a selected sprite

- **WHEN** 用户勾选“使用精灵”且当前颜色没有有效精灵选择
- **THEN** 系统显示精灵选择错误，并禁止绘制和确认

#### Scenario: Sprite preview uses library points

- **WHEN** 用户选择一个精灵并输入合法轨迹
- **THEN** 预览中的对象使用该精灵的点阵形状、尺寸和颜色，而不是单个普通颜色方块

### Requirement: Deterministic trajectory preview and generation

系统 SHALL 使用同一个确定性轨迹计算结果驱动预览和最终帧生成。轨迹 SHALL 包含起点和终点；步长大于 1 时跳过中间位置，步长小于 1 时允许重复位置但不得产生无界帧。每次有效配置改变时，系统 SHALL 停止旧预览并从第 0 帧重新播放；关闭弹窗 SHALL 清理预览定时器。

预览 SHALL 使用约 300ms 的编辑器播放节奏，且不得发送硬件报文、调用游戏运行接口或修改数据库。

#### Scenario: Preview and confirm use the same positions

- **WHEN** 用户点击绘制后观察预览，再点击确认
- **THEN** 预览中每一帧的对象位置、精灵形状和帧数与写入当前帧草稿的结果一致

#### Scenario: Configuration changes restart preview

- **WHEN** 用户修改颜色、起点、终点、步长、模式或精灵
- **THEN** 系统停止旧计时器，依据新配置从第 0 帧重新播放

### Requirement: Frame insertion and merge semantics

系统 SHALL 在“当前帧前插入”模式下，将生成的特效帧插入当前帧位置并保留原当前帧内容在特效序列之后。系统 SHALL 在“从当前帧开始合并”模式下，将特效对象叠加到当前帧及后续帧，必要时追加空帧；合并不得删除原有对象。

生成的普通矩形 SHALL 按 `width × height` 拆分为 1×1 点阵对象；生成的精灵 SHALL 保持精灵点阵作为一个对象，并在连续帧中复用同一对象 UID。

#### Scenario: Insert before current frame preserves original frame

- **WHEN** 用户选择插入模式并确认包含 3 帧的特效
- **THEN** 当前帧位置开始出现 3 帧特效，原当前帧内容在特效序列之后仍然存在

#### Scenario: Merge preserves existing objects

- **WHEN** 用户选择合并模式并确认特效覆盖已有对象的帧
- **THEN** 既有对象仍保留，特效对象叠加到对应帧，且不足的后续帧自动创建

### Requirement: Persistence through existing editor save

系统 SHALL 在点击特效弹窗的确认按钮时只更新当前编辑器内存中的 `frameList` 草稿；只有用户随后点击外层游戏保存时，才通过现有游戏编辑器保存链路写入关卡 `levels` 数据。取消、校验失败或保存请求失败时 SHALL 保留原始帧和未提交草稿。

#### Scenario: Confirm then save persists generated frames

- **WHEN** 用户确认合法特效并点击外层游戏保存
- **THEN** 现有游戏编辑器保存接口写入包含生成帧的关卡数据，重新打开该游戏和关卡后仍能看到这些帧

#### Scenario: Confirm does not call hardware or backend immediately

- **WHEN** 用户在特效弹窗中点击确认
- **THEN** 系统只修改前端内存草稿，不发送地砖/外围灯报文，不新增特效专用接口请求

### Requirement: Regression safety

系统 SHALL 保证特效弹窗不会改变现有矩阵编辑、关卡切换、符号灯布局配置、精灵库管理和游戏运行入口的行为。

#### Scenario: Existing matrix edits survive effect dialog

- **WHEN** 用户先修改矩阵但未保存，再打开并取消特效弹窗
- **THEN** 未保存的矩阵修改仍存在，且没有被特效草稿覆盖

#### Scenario: Existing pixel-light layout survives effect save

- **WHEN** 用户在同一游戏中打开符号灯布局、保存布局，再打开特效并保存游戏
- **THEN** `commonConfig.pixelLightWiring` 仍保持原值，特效帧单独写入关卡帧数据
