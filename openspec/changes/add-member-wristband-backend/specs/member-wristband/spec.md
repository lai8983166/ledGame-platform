## ADDED Requirements

### Requirement: 会员档案管理
系统 SHALL 以手机号为唯一身份维护会员档案，覆盖姓名、生日、性别、头像等基本字段，并为每次创建和修改保留操作来源（kiosk-id 或 admin-id）。

#### Scenario: 注册新会员
- **WHEN** kiosk 或 admin 提交一个未被 ACTIVE 会员占用的手机号及必填资料
- **THEN** 系统创建一个 `ACTIVE` 状态的会员
- **AND** 记录 `created_by` 为调用方标识

#### Scenario: 手机号已被 ACTIVE 会员占用
- **WHEN** 提交的手机号已存在于一个 `ACTIVE` 会员
- **THEN** 系统拒绝创建并返回明确的冲突提示
- **AND** 不写入任何新记录

### Requirement: 会员资料修改
系统 SHALL 允许对任意会员字段进行修改，包括手机号；修改手机号时仍受 ACTIVE 唯一约束。

#### Scenario: 修改手机号到未占用号码
- **WHEN** admin 把某会员的手机号改为一个未被 ACTIVE 会员占用的号码
- **THEN** 系统更新该会员手机号
- **AND** 记录修改审计（who/when）

#### Scenario: 修改手机号到已被占用号码
- **WHEN** 新手机号已被另一个 ACTIVE 会员占用
- **THEN** 系统拒绝修改并提示冲突

### Requirement: 会员冻结与解冻
系统 SHALL 支持把 ACTIVE 会员转为 FROZEN，并把 FROZEN 恢复为 ACTIVE。冻结后不允许为该会员发新手环；进行中的绑定保留至到期。

#### Scenario: 冻结会员
- **WHEN** admin 把某 ACTIVE 会员置为 FROZEN
- **THEN** 系统更新该会员状态为 FROZEN
- **AND** 该会员已有的 ACTIVE 绑定按原 expire_at 继续运行至到期
- **AND** 该会员不允许再发新手环

#### Scenario: 冻结后手机号可再注册
- **WHEN** 某手机号对应的会员处于 FROZEN 状态
- **AND** kiosk 或 admin 用同一手机号注册新会员
- **THEN** 系统允许创建新的 ACTIVE 会员
- **AND** 原 FROZEN 会员作为历史记录保留，不物理删除

#### Scenario: 解冻会员
- **WHEN** admin 把某 FROZEN 会员恢复为 ACTIVE
- **AND** 其手机号当前未被其他 ACTIVE 会员占用
- **THEN** 系统更新状态为 ACTIVE 并恢复发卡能力

### Requirement: 手环入库
系统 SHALL 通过 admin 批量录入或 kiosk 刷卡两种方式，把物理手环的 IC UID 登记为 IN_STOCK 状态。`card_uid` 在全表内 UNIQUE。

#### Scenario: admin 批量录入
- **WHEN** admin 在管理端提交一个或多个 10 位十六进制 IC UID
- **THEN** 系统为每个新 UID 创建一条 IN_STOCK 手环记录
- **AND** 对已存在的 UID 返回冲突提示但不影响其他新记录的创建

#### Scenario: kiosk 刷卡入库
- **WHEN** kiosk 处于入库模式并读到一个未登记的 UID
- **THEN** 系统创建一条 IN_STOCK 手环记录并返回成功
- **AND** 记录 `created_by` 为该 kiosk-id

#### Scenario: 重复入库
- **WHEN** 入库时提交的 UID 已存在
- **THEN** 系统拒绝重复入库并返回冲突提示

### Requirement: 手环发卡
系统 SHALL 把一个 IN_STOCK 手环绑定到指定会员，创建一条 BOUND 状态的 WristbandBinding。一个手环同时只能存在一条非终态绑定。

#### Scenario: kiosk 自助发卡
- **WHEN** 会员在 kiosk 完成建档并刷手环
- **AND** 该 UID 对应的手环处于 IN_STOCK
- **THEN** 系统创建 BOUND 状态的绑定（`duration_minutes = NULL`）
- **AND** 手环状态改为 BOUND
- **AND** 写入 `bound_at = now`、`issued_by = <kiosk-id>`

#### Scenario: 发卡到非库存手环
- **WHEN** 目标手环状态不是 IN_STOCK（已绑定/已退役等）
- **THEN** 系统拒绝发卡并提示当前不可用

#### Scenario: 给 FROZEN 会员发卡
- **WHEN** 目标会员状态为 FROZEN
- **THEN** 系统拒绝发卡并提示会员已被冻结

### Requirement: 手环时长充值
系统 SHALL 允许 admin 为一条 BOUND 状态的绑定录入 `duration_minutes`，作为激活后的计时依据。duration 由调用方传入，不从任何全局配置读取。

#### Scenario: 充值时长
- **WHEN** admin 为某 BOUND 绑定输入正整数分钟数并提交
- **THEN** 系统把 `duration_minutes` 写入该绑定
- **AND** 写入一条时长变更审计记录

#### Scenario: 充值到非 BOUND 绑定
- **WHEN** 目标绑定状态不是 BOUND（已激活/已结束/已取消）
- **THEN** 系统拒绝充值并提示当前不可改

#### Scenario: 充值非正整数
- **WHEN** 输入的分钟数不是正整数
- **THEN** 系统拒绝并返回校验错误

### Requirement: 手环激活
系统 SHALL 在桌面端首次刷卡时把 BOUND 绑定转为 ACTIVE，并计算 `expire_at = activated_at + duration_minutes`。激活的前置条件是 `duration_minutes` 不为 NULL。

#### Scenario: 已充值手环首次刷卡
- **WHEN** 桌面端上报某 UID 的首次刷卡
- **AND** 对应绑定处于 BOUND 且 `duration_minutes IS NOT NULL`
- **THEN** 系统把绑定和手环状态都改为 ACTIVE
- **AND** 写入 `activated_at = now`、`expire_at = now + duration_minutes`

#### Scenario: 未充值手环刷卡
- **WHEN** 桌面端上报某 UID 的首次刷卡
- **AND** 对应绑定的 `duration_minutes` 为 NULL
- **THEN** 系统返回"未充时长，请联系柜台"提示
- **AND** 状态保持 BOUND 不变

#### Scenario: 已激活手环再次刷卡
- **WHEN** 桌面端上报某 UID 的刷卡
- **AND** 对应绑定已处于 ACTIVE
- **THEN** 系统返回当前会员和剩余时长，不重复激活

### Requirement: 时长到期结束
系统 SHALL 在 ACTIVE 绑定的 `expire_at ≤ now` 时把它转为 USED_UP。手环状态同步进入 USED_UP。

#### Scenario: 时长自然到期
- **WHEN** 某 ACTIVE 绑定的 expire_at 早于当前时间
- **THEN** 系统把绑定和手环状态都改为 USED_UP
- **AND** 写入 `ended_at = now`

#### Scenario: admin 主动结束
- **WHEN** admin 对一个 ACTIVE 绑定执行"提前结束"
- **THEN** 系统把绑定和手环状态都改为 USED_UP
- **AND** 写入 `ended_at = now`、记录 `ended_by`

### Requirement: 手环归还
系统 SHALL 允许 admin 把一个 USED_UP 手环归还回 IN_STOCK，使其可被再次发卡。

#### Scenario: 归还用完的手环
- **WHEN** admin 对一个 USED_UP 手环执行归还
- **THEN** 系统把手环状态改为 IN_STOCK
- **AND** 当前绑定保持 USED_UP 状态作为历史记录

#### Scenario: 归还未到期的手环
- **WHEN** admin 对一个仍处于 ACTIVE 的手环执行归还
- **THEN** 系统先把绑定置为 USED_UP（写 ended_at）
- **AND** 然后把手环状态改为 IN_STOCK

### Requirement: 手环退役
系统 SHALL 允许 admin 把任意状态的手环转为 RETIRED，永久下架。退役手环不再出现在可发卡库存中。

#### Scenario: 退役损坏手环
- **WHEN** admin 对一个手环执行退役并填入原因
- **THEN** 系统把手环状态改为 RETIRED
- **AND** 写入 `notes` 或退役原因
- **AND** 该手环不能再被发卡、激活或归还

### Requirement: 多手环同时绑定
系统 SHALL 允许一个会员同时持有多条非终态（BOUND/ACTIVE）的 WristbandBinding，各自独立计时。

#### Scenario: 同一会员再发一张手环
- **WHEN** 会员 A 当前已有一条 ACTIVE 绑定
- **AND** admin 又给 A 发了一张新手环
- **THEN** 系统创建第二条 BOUND 绑定
- **AND** 两条绑定独立计时、独立激活

### Requirement: 多手环数据归属
系统 SHALL 把游戏积分、游玩记录等业务数据归到 Member 而非 WristbandBinding；只有"时段相关"的数据（duration、activated_at、expire_at）按绑定独立。

#### Scenario: 多手环积分累计到同一会员
- **WHEN** 会员 A 持有手环 #1 和 #2，桌面端分别上报两次游戏积分
- **THEN** 两次积分都归属 A 的会员账户
- **AND** 时长按各自绑定独立计算

### Requirement: 未激活绑定的取消
系统 SHALL 允许 admin 取消一条 BOUND 绑定，把对应手环释放回 IN_STOCK。

#### Scenario: 取消未激活绑定
- **WHEN** admin 对一条 BOUND 绑定执行取消，并填入原因
- **THEN** 系统把绑定状态改为 CANCELLED
- **AND** 手环状态回到 IN_STOCK
- **AND** 写入 `cancelled_by`、`cancel_reason`

#### Scenario: 取消已激活绑定
- **WHEN** admin 对一条 ACTIVE 绑定执行取消
- **THEN** 系统拒绝直接取消
- **AND** 提示 admin 应使用"提前结束"使其进入 USED_UP 再归还

### Requirement: 续杯（重新发卡）
系统 SHALL 把"延长时长"实现为一次完整的"归还 + 重新发卡"操作：旧绑定归档为 USED_UP，手环回 IN_STOCK，立即创建一条新的 BOUND 绑定（duration 由 admin 输入）。可在同手环或新手环上进行。

#### Scenario: 续杯（同手环）
- **WHEN** 会员当前绑定的手环处于 USED_UP
- **AND** admin 执行"归还 + 重新发卡"使用同一个物理手环并输入新 duration
- **THEN** 系统保持旧绑定 USED_UP 状态作为历史
- **AND** 手环状态先回 IN_STOCK，再立即创建一条新 BOUND 绑定
- **AND** 新绑定的 `duration_minutes` = admin 输入值

#### Scenario: 续杯（新手环）
- **WHEN** 会员当前手环已 USED_UP
- **AND** admin 选择另一张 IN_STOCK 手环重新发卡
- **THEN** 旧手环保持 USED_UP 等待归还
- **AND** 新手环创建一条 BOUND 绑定

### Requirement: 手环物理标识
系统 SHALL 以 10 位十六进制字符串的 IC UID 作为手环的物理唯一标识，并在 `wristband.card_uid` 上建立 UNIQUE 约束。

#### Scenario: 同 UID 入库冲突
- **WHEN** 入库时提交的 UID 已存在
- **THEN** 系统拒绝重复入库并返回冲突提示

#### Scenario: UID 格式校验
- **WHEN** 提交的 UID 不是 10 位十六进制字符串
- **THEN** 系统拒绝并返回格式校验错误
