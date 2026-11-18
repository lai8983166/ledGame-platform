# game-editor-effect-configuration Specification

## Purpose

为 `simple`、`normal` 和 `diffcult` 共用的游戏编辑器提供可视化特效配置、预览和帧草稿生成能力，让特效能够复用精灵库点阵并通过现有游戏保存流程持久化。

## Requirements

### Requirement: Shared effect editor entry

系统 SHALL 在 `simple`、`normal` 和 `diffcult` 共用编辑器中提供“特效”入口；点击后 SHALL 打开独立配置弹窗，并加载当前关卡、当前帧和当前场地尺寸。

#### Scenario: Open effect editor for each default game

- **WHEN** 用户在任意一个默认玩法的编辑器中点击“特效”按钮
- **THEN** 系统打开同一套特效弹窗，并显示对应当前关卡和当前帧的草稿

#### Scenario: Cancel keeps the game editor unchanged

- **WHEN** 用户修改特效参数后点击取消或关闭弹窗
- **THEN** 系统停止预览、关闭弹窗，且当前关卡的帧内容和外层未保存状态均保持不变

### Requirement: Effect configuration fields and validation

系统 SHALL 支持当前帧前插入和当前帧后插入两种模式、颜色、起点、终点、矩形宽高、步长及“使用精灵”开关。旧合并模式不再作为界面选项，历史合并草稿进入界面时按后插入显示。场地尺寸 SHALL 只读显示。坐标输入使用从 1 开始的界面坐标，生成帧时转换为从 0 开始的内部坐标。

系统 SHALL 拒绝以下配置并在对应控件附近显示可理解的错误：起点或终点超出场地、矩形宽高小于 1 或超出场地、步长不是有限正数、使用精灵但没有可用精灵、以及精灵点阵放置后超出场地。

#### Scenario: Invalid coordinates are rejected

- **WHEN** 用户输入超出当前场地范围的起点或终点，触发自动预览或点击确认
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
- **THEN** 系统显示精灵选择错误，停止无效预览并禁止确认

#### Scenario: Sprite preview uses library points

- **WHEN** 用户选择一个精灵并输入合法轨迹
- **THEN** 预览中的对象使用该精灵的点阵形状、尺寸和颜色，而不是单个普通颜色方块

### Requirement: Deterministic trajectory preview and generation

系统 SHALL 使用同一个确定性轨迹计算结果驱动预览和最终帧生成。轨迹 SHALL 包含起点和终点；步长大于 1 时跳过中间位置，步长小于 1 时允许重复位置但不得产生无界帧。每次有效配置改变时，系统 SHALL 停止旧预览并从第 0 帧重新播放；关闭弹窗 SHALL 清理预览定时器。

预览 SHALL 使用约 300ms 的编辑器播放节奏，且不得发送硬件报文、调用游戏运行接口或修改数据库。

#### Scenario: Preview and confirm use the same positions

- **WHEN** 用户配置有效参数后观察自动预览，再点击确认
- **THEN** 预览中每一帧的对象位置、精灵形状和帧数与写入当前帧草稿的结果一致

#### Scenario: Configuration changes restart preview

- **WHEN** 用户修改颜色、起点、终点、步长、模式或精灵
- **THEN** 系统停止旧计时器，依据新配置从第 0 帧重新播放
- **AND** 界面不要求点击手动重新绘制按钮

### Requirement: Frame insertion semantics

系统 SHALL 在“当前帧前插入”模式下，将生成的特效帧插入当前帧位置并保留原当前帧内容在特效序列之后。在“当前帧后插入”模式下，系统 SHALL 将生成序列插入当前帧之后，不合并、覆盖或删除原帧及已有后续帧。

生成的普通矩形 SHALL 按 `width × height` 拆分为 1×1 点阵对象；生成的精灵 SHALL 保持精灵点阵作为一个对象，并在连续帧中复用同一对象 UID。

#### Scenario: Insert before current frame preserves original frame

- **WHEN** 用户选择插入模式并确认包含 3 帧的特效
- **THEN** 当前帧位置开始出现 3 帧特效，原当前帧内容在特效序列之后仍然存在

#### Scenario: Insert after current frame preserves original frames

- **WHEN** 用户选择当前帧后插入并确认包含3帧的特效
- **THEN** 原当前帧保留，随后出现3帧特效，再接原后续帧；原帧对象不被合并或覆盖

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

### Requirement: 预览显示生成帧数并确认应用影响

特效预览 SHALL 在第x/n帧进度右侧显示本次实际生成帧数，参数改变后同步更新。合法且有数据变动的特效应用 SHALL 在帧序列及历史改变之前弹窗确认，列出关卡、写入方式、生成数、实际写入帧范围、改动原有帧数、新增帧数及应用前后总帧数；覆盖尾部溢出的新增帧 SHALL 准确计算。取消确认 SHALL 保留特效参数与预览、原帧及撤销/重做历史；接受后 SHALL 按原流程形成一步可撤销事务。无效、占用拒绝及无数据变化 SHALL 不弹应用确认。

#### Scenario: 生成数与插入确认
- **WHEN** 调整终点或步长，选择当前帧前/后插入并应用
- **THEN** 生成数与实际预览帧数一致且位于进度右侧，确认显示正确插入范围与增加后的总数，接受才写入

#### Scenario: 覆盖追加与取消
- **WHEN** 在5帧关卡的第5帧开始覆盖生成3帧，或取消应用确认
- **THEN** 显示改动1个原帧、追加2帧及总数5变7，取消不改帧或历史并可继续编辑特效，接受后仍能一步撤销和重做

### Requirement: 整次特效应用一步撤销和重做

共用Simple、Normal及Diffcult编辑器 SHALL 将一次成功的特效应用作为现有撤销/重做历史中的一步，覆盖当前帧前插入、后插入、从当前帧覆盖和合并模式。撤销 SHALL 恢复该关应用前的完整帧序列，包括数量、顺序、帧内容、重复次数及附加字段，并恢复编辑位置与可用的对象选择；重做 SHALL 精确恢复同次应用结果，不重新生成对象身份。特效 SHALL 保留既有RGB历史，与之前及之后的普通矩阵编辑按顺序撤销/重做；普通RGB历史不扩展为整关快照。其他关卡配置及游戏设置 SHALL 不因特效历史恢复被覆盖。

#### Scenario: 插入或覆盖后撤销及重做
- **WHEN** 确认一次插入、覆盖或合并特效，再执行一次撤销或重做
- **THEN** 原完整帧序列或该次生成结果准确恢复，覆盖追加的帧可一起撤回，原帧重复次数及对象字段保留

#### Scenario: 与普通编辑衔接
- **WHEN** 普通RGB编辑、特效应用及后续RGB编辑依次发生，再连续撤销和重做
- **THEN** 每步按实际操作顺序恢复，原帧在插入导致索引变化后仍可正确恢复，特效不清空此前记录

#### Scenario: 取消及无效操作
- **WHEN** 取消特效、参数非法、占用校验拒绝或操作没有实际数据改变
- **THEN** 不改变帧序列或增加历史，保留既有重做；新的有效编辑按原规则清除重做

### Requirement: 从当前帧覆盖特效
特效写入方式 SHALL 增加“从当前帧开始覆盖”，按生成的完整帧逐帧替换当前帧起的原帧，超过原长度时补帧，保留未覆盖的前后原帧。确认只改变草稿，取消不写入。步长输入框增减单位 SHALL 为1，手动输入也 SHALL 为正整数，非法值不得生成或写入。

#### Scenario: 覆盖并补帧
- **WHEN** 在中间帧选择覆盖并确认生成序列，或生成序列超过原有尾帧
- **THEN** 从当前帧替换对应完整帧并按需追加，其他帧不变，预览和保存内容一致

#### Scenario: 调整步长或取消
- **WHEN** 使用步长增减按钮，或修改特效后取消
- **THEN** 每次步长增减1，取消不修改原帧
