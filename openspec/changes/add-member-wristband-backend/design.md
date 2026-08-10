## Context

`ledGame-platform` 是一个面向单门店独立部署的中心后端，承载会员、注册、权限和跨端数据同步。三端（自助注册端、会员管理端、游戏桌面端）共享同一份后端，但**每家门店的整套三端系统彼此独立**，没有跨门店的中心 DB。

会员 + 手环是整个系统最核心的域：

- 账户余额、流水挂在会员上
- 游戏积分、游玩记录挂在会员上
- 跨端同步的核心事件是手环状态变更
- 审计追溯的最小单位是"谁、何时、对哪个手环/会员做了什么"

因此这个域必须先于其他模块设计稳定。

本 change 的范围**严格限制在 Member + Wristband + WristbandBinding 三个聚合**。涉及账户扣款、跨端推送、认证授权、审计查询等周边能力，本 change 只产出"领域事件"或"接口契约"，不实现消费方。

## Goals / Non-Goals

**Goals:**

- 定义三个聚合的数据模型、字段、约束、索引。
- 定义手环和绑定关系的完整状态机和流转规则。
- 定义发卡、激活、续杯、归还、取消、入库、退役等核心流程的行为契约。
- 明确手机号、IC UID、多绑定、duration 来源等关键决策，并给出理由。
- 为后续所有模块（账户、记录、同步、认证）留出清晰的接缝。

**Non-Goals:**

- 不实现账户余额、套餐、消费扣款（账户模块）。
- 不实现跨端实时推送、桌面端长连接（同步模块）。
- 不实现管理员登录、角色权限（认证模块）。
- 不实现审计日志的查询和展示界面（记录模块）。
- 不接入支付通道、不实现真实读写卡硬件。
- 不实现 UI 接线——kiosk 和 member-admin 当前仍用模拟数据。

## Decisions

### 1. 三表设计：Member + Wristband + WristbandBinding

把"绑定关系"独立成第三张表，而不是把当前绑定写在 Member 或 Wristband 上。

**理由**：手环是反复使用的——顾客 A 今天用，归还后顾客 B 下周领到同一个手环。如果绑定关系直接写在 Wristband 上，历史信息会被覆盖，无法回答"手环 #1233 上周三被谁用过"。独立 Binding 表保留全部历史，是审计和纠纷处理的基础。

### 2. 手机号作为会员唯一身份（UNIQUE）

一个手机号对应一个 ACTIVE 会员。手机号在 `member.phone` 上有 UNIQUE 约束（与 status 配合的 partial unique，允许 FROZEN 会员的手机号被复用，见决策 14）。

**理由**：手机号是顾客最稳定可记忆的标识；kiosk 注册流程也以手机号为主键查询入口。

### 3. IC 卡 UID 作为手环物理标识

`wristband.card_uid` 是 10 位十六进制字符串，UNIQUE。读卡器读出的原始 UID 即手环身份。

**理由**：IC 卡 UID 是物理层面的唯一标识，不需要再编一个内部编号。10 位十六进制对索引友好。

### 4. 一个会员可同时绑定多个手环

允许一个 Member 当前有多条非终态（BOUND/ACTIVE）的 WristbandBinding。一个 Wristband 同时只能有一条非终态绑定。

**理由**：典型场景是家长带孩子，一人一张手环分别计时；或者一个顾客玩两类设备需要两张卡。

### 5. duration 由调用方传入，不设全局配置

`WristbandBinding.duration_minutes` 由发卡/续杯操作的调用方传入。**不**引入 `setting.default_duration` 之类的全局配置项。

**理由**：时长是"顾客付多少钱决定充多少"的商业行为，不是系统配置。把它做成全局设置会误导成"系统参数"，且无法支持不同顾客充不同时长。

### 6. 时长到期自动结束（→ USED_UP）

ACTIVE 绑定在 `expire_at ≤ now` 时自动转为 USED_UP，无需 admin 介入。Wristband 同步进入 USED_UP。手环在 USED_UP 状态下无法继续刷卡玩游戏，必须归还后才能再次发卡。

**理由**：按时长计费的产品语义就是"到点结束"。自动化降低运营负担。

### 7. 无挂失功能；保留 RETIRED 状态供 admin 下架

不实现会员侧的挂失流程。但保留 `RETIRED` 状态供 admin 处理损坏、丢失、淘汰的手环——这是库存管理侧的退役，不是会员侧的挂失。

**理由**：挂失涉及跨端实时同步（立即通知所有桌面端拒绝该卡），复杂度跳一级，当前不做。但物理手环的退役是基础库存管理，必须有。

### 8. 续杯 = 归还 + 重新发卡

会员想延长时长时，流程是：归还当前手环 → 用同手环或新手环重新发卡（admin 输入新时长）。不实现"延长 expire_at"或"在同一绑定上累加时长"。

**理由**：每次发卡都是一次独立的"购买"，需要独立的审计记录（who/when/how much）。如果允许在同一绑定上累加，账目会糊在一起。

### 9. 多手环数据归属：时长独立，积分/记录归 Member

WristbandBinding 只持有"本次时段"的数据（时长、激活/结束时间）。积分、游玩记录、消费流水等业务数据**归 Member**，不归 Binding。

**理由**：同一会员的多个手环本质是同一人的不同时段/不同设备。积分累计到 Member 才能形成"会员总积分"。

### 10. BOUND 绑定可由 admin 手动取消（→ CANCELLED）

未激活的绑定如果顾客不刷卡就走，admin 可以手动取消，手环回 IN_STOCK。不实现"自动超时取消"。

**理由**：自动超时会引入额外的定时任务和边界条件（超时多久？正在玩怎么算？）。当前规模下，店长手动处理成本更低。

### 11. 入库支持 admin 录入 + kiosk 刷卡两种方式

新购入的手环通过 admin 在管理端批量录入 UID，或通过 kiosk 的"入库模式"逐张刷卡登记。

**理由**：批量进货时 admin 录入快；零星补货或更换时 kiosk 刷卡方便。

### 12. 会员资料所有字段可改

包括手机号在内，所有字段都可修改。修改手机号时仍受 UNIQUE 约束。

**理由**：现实场景里"顾客输错手机号"是常见客服需求。强制不可改会让店长无法处理。

### 13. kiosk 发卡不发时长（duration = NULL）

kiosk 自助发卡流程只创建 BOUND 绑定，`duration_minutes = NULL`。会员必须去 admin 柜台付钱充值才能设定时长并激活玩。

**理由**：与核心商业流程一致（"顾客付钱 → admin 充值"）。kiosk 自助付款会引入支付集成，复杂度跳一级，当前不做。

### 14. FROZEN 会员的手机号可再注册新会员

冻结某会员后，其手机号解除独占，可以用于注册新 ACTIVE 会员。旧 FROZEN 会员作为历史记录保留。

**理由**：店长手抖冻错人、顾客输错手机号等情况都需要"换个号重开"。永久占用手机号会让客服流程卡死。

### 15. Wristband.status 与 WristbandBinding.status 双轨

两者都保留：

- `Wristband.status`：物理手环当前所处的库存/使用阶段（IN_STOCK / BOUND / ACTIVE / USED_UP / RETIRED）
- `WristbandBinding.status`：本次绑定关系的生命周期（BOUND / ACTIVE / USED_UP / CANCELLED）

Wristband.status 在多数情况下是"当前非终态绑定状态"的镜像，外加无绑定时的 IN_STOCK 和强制下架的 RETIRED。

**理由**：双轨是经典的去规范化（denormalization）。Wristband.status 让"列出所有可发卡手环"等查询一次完成；Binding.status 保留每次绑定的独立生命周期。所有状态变更在同一事务内同步。

## 数据模型

### Member

```
member
├─ id              BIGINT PK
├─ phone           VARCHAR(11)   ─ partial UNIQUE (status = ACTIVE)
├─ name            VARCHAR
├─ birthday        DATE NULL
├─ gender          TINYINT       ─ 0 未知 / 1 男 / 2 女
├─ avatar_id       VARCHAR       ─ 引用内置头像库（不上传图片）
├─ status          TINYINT       ─ ACTIVE / FROZEN
├─ created_at      DATETIME
├─ updated_at      DATETIME
└─ created_by      VARCHAR       ─ kiosk-id / admin-id
```

### Wristband

```
wristband
├─ id              BIGINT PK
├─ card_uid        CHAR(10)      ─ UNIQUE，十六进制
├─ status          TINYINT       ─ IN_STOCK / BOUND / ACTIVE / USED_UP / RETIRED
├─ notes           VARCHAR NULL  ─ 损坏备注、退役原因等
├─ created_at      DATETIME
├─ updated_at      DATETIME
└─ created_by      VARCHAR
```

### WristbandBinding

```
wristband_binding
├─ id                  BIGINT PK
├─ wristband_id         BIGINT FK
├─ member_id            BIGINT FK
├─ status              TINYINT   ─ BOUND / ACTIVE / USED_UP / CANCELLED
├─ duration_minutes    INT NULL  ─ NULL = 未充值，激活前置条件
├─ bound_at            DATETIME  ─ 发卡时刻
├─ activated_at        DATETIME NULL ─ 桌面端首次刷卡时填
├─ expire_at           DATETIME NULL ─ 激活时计算 = activated_at + duration
├─ ended_at            DATETIME NULL ─ 实际结束（到期/手动）
├─ issued_by           VARCHAR   ─ kiosk-id / admin-id
├─ cancelled_by        VARCHAR NULL
├─ cancel_reason       VARCHAR NULL
├─ created_at          DATETIME
└─ updated_at          DATETIME

索引：
  UNIQUE (wristband_id) WHERE status IN (BOUND, ACTIVE)  ─ 一个手环同时只能有一条非终态绑定
  INDEX (member_id, status)                              ─ 查会员当前绑定
```

### 关系总览

```
        ┌──────────┐
        │  Member  │◀──────────────────────┐
        │  (1)     │                        │
        └────┬─────┘                        │
             │ 1                            │
             │                              │ *
             │ *        ┌──────────────┐    │
             └─────────▶│WristbandBinding│◀───┘
                        │   (桥)        │
                        └──────┬───────┘
                               │ *
                               │
                               │ *
                        ┌──────▼─────┐
                        │ Wristband  │
                        │  (1)       │
                        └────────────┘
```

## 状态机

### Wristband 状态

```
              IN_STOCK
                 │
                 │ 发卡(issue)
                 │   + 新建 Binding(BOUND)
                 ▼
              ┌─BOUND──────┐
              │ 未激活      │──── admin 取消 ──▶ Binding:CANCELLED
              │(duration NULL)│                   (Wristband → IN_STOCK)
              └─────┬──────┘
                    │ 桌面端首次刷卡
                    │   前置: duration_minutes IS NOT NULL
                    │   → Binding:BOUND→ACTIVE
                    │   → 写 activated_at + expire_at
                    ▼
              ┌─ACTIVE─────┐
              │ 计时中      │
              └─────┬──────┘
                    │ expire_at ≤ now（自动）
                    │ 或 admin 主动结束
                    │   → Binding:ACTIVE→USED_UP
                    │   → 写 ended_at
                    ▼
              ┌─USED_UP────┐
              │ 用完待归还  │ ◀── 此时刷卡会被桌面端拒绝
              └─────┬──────┘
                    │ 物理归还（admin 操作）
                    │   → Wristband → IN_STOCK
                    ▼
              IN_STOCK

   ─── 任意状态 ───▶ RETIRED  (admin 永久下架)
```

### Member 状态

```
   (注册) ──▶ ACTIVE ◀──────┐
                 │          │
                 │ 冻结      │ 解冻
                 ▼          │
              FROZEN ───────┘
                 │
                 │ FROZEN 后手机号可被新 ACTIVE 会员占用
                 │ （旧 FROZEN 会员作为历史保留）
                 ▼
              （保留为历史，不物理删除）
```

## Risks / Trade-offs

- **多绑定带来的复杂性**：同一会员多条 ACTIVE 绑定并存，管理员 UI 必须能清晰展示"哪张手环在哪个状态"。否则店长会看不懂。→ 由后续 member-admin 接线 change 处理 UI。
- **Wristband.status 双轨同步**：状态变更必须在同一事务内同步两个 status，否则会不一致。→ 用领域服务统一入口，禁止直接 update 单边。
- **duration 快照 vs 引用**：duration_minutes 是快照写在 Binding 上，不引用任何"套餐表"。如果后续要做套餐体系，套餐变更不影响进行中的绑定。→ 简单且安全。
- **未激活绑定不自动作废**：库存可能被"占着不刷"的手环占用。→ 当前由 admin 手动取消；如果未来发现是高频问题，再加超时规则。
- **FROZEN 会员的现有绑定如何处理**：本 change 默认"冻结不影响进行中的绑定（保留至到期）"。如果业务要求"冻结立即失效"，需要补一条决策。→ 留作小问题，在实现时再问业务方。
