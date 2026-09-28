## Purpose

让不同玩法只受到其实际使用的硬件影响，使未使用地砖的玩法能够在 ELC 控制器不可用时正常运行，同时保持现有地砖玩法的输入输出、模拟调试、待机表现和三端业务兼容性，支持后续加入其他类型玩法。

## ADDED Requirements

### Requirement: Hardware requirements follow the selected gameplay

系统 SHALL 依据当前选择的玩法执行其硬件准备检查与操作。未使用地砖的玩法 MUST 能在全局 ELC 输出启用但 SDK 关闭、配置不可用或控制器离线时正常启动和结束，不触发地砖帧发送或外围灯初始化。

#### Scenario: Non-floor gameplay with unavailable ELC hardware
- **WHEN** 使用不依赖地砖的已注册玩法，且 ELC 输出全局启用但硬件不可用
- **THEN** 玩法按自身规则启动、运行和结束，不返回 ELC 就绪错误，不发送该玩法的地砖/符号灯数据

#### Scenario: Existing floor gameplay in production
- **WHEN** Simple、Normal、Diffcult 或 Rank 在正式模式下确认开始且 ELC 输出启用
- **THEN** 系统沿用现有硬件检查和错误提示，硬件未就绪时不开始本局游玩计时，触屏、手环与投币入口使用同一规则

### Requirement: Existing floor output remains equivalent

现有地砖玩法 SHALL 保持原矩阵尺寸、颜色、坐标、RGB 编码、序号及异步发送行为。正常运行、游戏开始、关卡过渡和结算/结束媒体的地砖帧 MUST 使用当前玩法的输出路径。

#### Scenario: Floor frames and media playback
- **WHEN** 同一地砖玩法使用相同配置和输入执行游戏帧或地砖动画
- **THEN** 控制器和 DebugPanel 获得与重构前一致的帧内容，帧不会被重复发送或发往上一局

### Requirement: Physical and simulated floor input remain compatible

现有地砖玩法 SHALL 保持真实踩踏与模拟踩踏的相同处理逻辑，包括 DOWN、UP、RESET、坐标校验及输入去重。地砖输入 MUST 不改变未使用地砖玩法的游戏状态。

#### Scenario: Debug without a controller
- **WHEN** 模拟模式启动现有地砖玩法且未连接控制器，随后在 DebugPanel 操作
- **THEN** 游戏正常启动，模拟输入、得分及关卡结果遵循现有规则

#### Scenario: Debug with available hardware
- **WHEN** 模拟模式启动地砖玩法且 SDK 已启用、控制器可用
- **THEN** 玩法仍可驱动真实地砖并接受其输入，与模拟输入共用玩法处理逻辑

#### Scenario: Floor event during non-floor gameplay
- **WHEN** 未使用地砖的玩法运行期间收到旧地砖入口或物理控制器的踩踏/RESET 事件
- **THEN** 该事件不影响其分数、状态、计时或参与会员

### Requirement: Peripheral lights retain per-game behavior

使用外围灯的玩法 SHALL 保持每局冻结布局、按开关启用及异常降级规则，结束时清理本局状态。当前未接通的物理符号灯输出 MUST 保持未接通，不因重构产生未经验证的设备报文。

#### Scenario: Disabled or invalid peripheral configuration
- **WHEN** 地砖游戏缺失符号灯配置、关闭开关或存在无效配置
- **THEN** 外围灯关闭，地砖玩法继续沿用原行为

#### Scenario: Switching games after termination
- **WHEN** 游戏自然结束、手动中止、启动失败或异常退出后开始下一局
- **THEN** 本局外围灯与输入状态清理完整且重复清理安全，上一局晚到输出或清理不干扰下一局

### Requirement: Standby and device observation remain usable

系统 SHALL 保持当前系统待机、准备页矩阵和配置的待机画面，且运行中的玩法不被旧待机输出干扰。设备搜索、SDK 工具及房间硬件状态上报 SHALL 保持现有独立功能和每 60 秒异步搜索策略。

#### Scenario: Standby and preparation transitions
- **WHEN** 应用启动、进入配置步骤、取消准备或结束游戏回到待机
- **THEN** 地砖与 DebugPanel 继续显示原来的待机/准备内容，Touch 和副屏待机画面及动画保持原样

#### Scenario: Health check while a non-floor game runs
- **WHEN** 不使用地砖的玩法运行期间硬件搜索报告 ELC 离线
- **THEN** 房间信息反映硬件状态，当前玩法继续正常运行且不被地砖待机帧干扰

### Requirement: Business and configuration compatibility

系统 MUST 保持现有游戏匹配、游戏配置、HTTP/IPC 入口、运行时状态和客户操作步骤，保持会员放行、RUNNING 扣时、积分结算、游戏记录、排队及通信重连行为，无须迁移数据库或为旧游戏重新选择硬件。

#### Scenario: Multiplayer wristband and queue workflow
- **WHEN** 多位会员通过既有手环流程启动地砖玩法，完成或中止游戏，再启动排队的下一局
- **THEN** 放行、实际 RUNNING 时长、积分/记录、队列推进与房间状态保持现有规则，硬件启动失败不产生错误游玩扣时或成功记录

#### Scenario: Existing game and device configuration
- **WHEN** 使用重构前保存的游戏和门店 SDK/布线文件启动现有玩法
- **THEN** 游戏正常加载并使用原配置，游戏名称、type/mode、布线字段和符号灯字段不需改写
