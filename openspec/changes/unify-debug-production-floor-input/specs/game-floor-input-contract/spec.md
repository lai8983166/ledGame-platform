## ADDED Requirements

### Requirement: 游戏规则消费统一的地砖输入事件
系统 SHALL 在输入适配边界将 Debug、HTTP 和 ELC-408/TCP 输入规范化为来源无关的 `DOWN`、`UP` 或 `RESET` 地砖事件，游戏规则不得根据输入来源选择不同的得分、扣血或目标判定实现。

#### Scenario: Debug 面板模拟一次踩踏
- **WHEN** 操作者在 Debug Panel 中点击一个可交互地砖或触发确定性自然完成动作
- **THEN** 系统按顺序产生该坐标的 `DOWN` 和 `UP` 事件
- **AND** 事件通过正常游戏输入端点和运行时分发进入当前游戏处理器

#### Scenario: ELC-408 上报真实地砖状态变化
- **WHEN** ELC-408 适配器将合法的物理按下或抬起状态映射为逻辑坐标
- **THEN** 系统产生相同契约的 `DOWN` 或 `UP` 事件
- **AND** 事件进入与 Debug 输入相同的运行时分发和游戏规则

#### Scenario: 输入连接重置
- **WHEN** 输入 TCP 连接断开、游戏停止或输入会话被替换
- **THEN** 系统向当前处理器应用 `RESET`
- **AND** 当前所有活动地砖和本次按住期间的交互记忆被清除

### Requirement: Simple Game 对不同输入来源产生一致结果
Simple Game SHALL 只通过统一地砖事件改变 gameplay state；同一游戏夹具和等价的地砖事件序列 MUST 产生相同的得分、生命、目标状态、关卡结果和终止快照。

#### Scenario: Debug 与真实地砖命中同一蓝色目标
- **WHEN** Debug 适配器和 ELC/TCP 适配器分别在同一夹具的目标坐标产生一次等价的 `DOWN -> UP`
- **THEN** 两次运行均只增加一次得分并完成同一目标
- **AND** 两次运行产生相同的自然关卡结果和结算原始分数

#### Scenario: 快速踩下并抬起发生在两个游戏帧之间
- **WHEN** 同一坐标的 `DOWN` 和 `UP` 在下一次游戏 tick 前到达
- **THEN** 游戏仍处理一次且仅一次交互
- **AND** 事件顺序不会因 Debug、HTTP 或 TCP 来源而改变

#### Scenario: 持续踩住跨越移动物体
- **WHEN** 一个坐标保持 `DOWN` 并且不同游戏物体依次经过该坐标
- **THEN** 游戏按照统一的按住语义处理不同物体
- **AND** 同一物体在同一次按住期间不会被重复计分或扣血

### Requirement: 非法地砖输入不得改变游戏状态
输入边界 SHALL 拒绝缺少坐标、越界坐标、未知动作或非法按下值的地砖事件，并且 MUST 保持当前 gameplay state 不变。

#### Scenario: 收到非法输入载荷
- **WHEN** Debug、HTTP 或 TCP 适配器产生不符合地砖输入契约的载荷
- **THEN** 系统返回或记录稳定的输入错误
- **AND** 不执行得分、扣血、目标完成或关卡推进

### Requirement: 调试生命周期指令与正常游戏输入隔离
强制关卡结果、重试、跳关和 `End Game` SHALL 继续作为仅限 `SIMULATION` 的调试指令，并且不得被视为正常地砖输入或游戏规则验证。

#### Scenario: 通过地砖自然完成游戏
- **WHEN** 自动化场景需要证明得分规则和自然结算正确
- **THEN** 场景使用规范化地砖输入满足游戏目标
- **AND** 场景不得调用强制成功或 `End Game` 代替自然完成

#### Scenario: 验证主动中止行为
- **WHEN** 自动化场景专门验证操作者主动结束游戏
- **THEN** 场景可以调用 `End Game`
- **AND** 结果必须保持为中止而不是自然成功

