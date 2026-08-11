## 1. 数据模型与持久化

- [ ] 1.1 在 `server/` 中规划 `member`、`wristband` 两个顶层包，并定义对应的领域模型类（Member、Wristband、WristbandBinding）及枚举（MemberStatus、WristbandStatus、BindingStatus）
- [ ] 1.2 选定持久层方案（Spring Data JPA 或 MyBatis），并把决策记入实现说明
- [ ] 1.3 创建数据库迁移脚本（Flyway 或等价方案），建立 `member`、`wristband`、`wristband_binding` 三张表，含字段、约束、索引
- [ ] 1.4 为 `member.phone` 实现 ACTIVE 范围的唯一约束（partial unique 或等价机制，允许 FROZEN 会员复用手机号）
- [ ] 1.5 为 `wristband_binding` 实现"一个手环同时只能有一条非终态绑定"的唯一约束（`(wristband_id) WHERE status IN (BOUND, ACTIVE)`）
- [ ] 1.6 准备开发态种子数据（若干会员、若干手环、若干不同状态的绑定），便于本地验证

## 2. 会员域服务与契约

- [ ] 2.1 实现 `MemberService` 的注册能力：手机号唯一校验、ACTIVE 状态创建、记录 `created_by`
- [ ] 2.2 实现资料修改能力：全字段可改，含手机号修改时的 ACTIVE 唯一冲突检测
- [ ] 2.3 实现冻结与解冻能力：ACTIVE↔FROZEN 转换、解冻时校验手机号未被其他 ACTIVE 会员占用
- [ ] 2.4 实现按手机号、按 ID 查询会员（含当前 ACTIVE 绑定概览）
- [ ] 2.5 定义 `MemberEvent` 领域事件（MemberCreated / MemberUpdated / MemberFrozen / MemberUnfrozen），事件结构留给 Records 模块消费

## 3. 手环域服务与契约

- [ ] 3.1 实现 `WristbandService` 的入库能力：admin 批量录入与 kiosk 刷卡入库两种入口、UID 格式校验、UNIQUE 冲突处理
- [ ] 3.2 实现退役能力：任意状态 → RETIRED，记录退役原因
- [ ] 3.3 实现 IN_STOCK / BOUND / ACTIVE / USED_UP / RETIRED 状态查询与筛选

## 4. 绑定关系域服务与状态机

- [ ] 4.1 实现 `WristbandBindingService.issue()`：IN_STOCK 手环 + ACTIVE 会员 → 创建 BOUND 绑定（kiosk 路径 duration = NULL），事务内同步 Wristband.status = BOUND
- [ ] 4.2 实现 `topUpDuration()`：admin 为 BOUND 绑定写入 `duration_minutes`（带状态前置校验）
- [ ] 4.3 实现 `activate()`：桌面端首次刷卡 → BOUND→ACTIVE，校验 duration 非空，计算 activated_at + expire_at
- [ ] 4.4 实现 `expireActiveSessions()`：扫描 expire_at ≤ now 的 ACTIVE 绑定并转为 USED_UP（可由桌面端查询时懒触发或定时任务触发，二选一并记录决策）
- [ ] 4.5 实现 `manualEnd()`：admin 主动结束 ACTIVE 绑定 → USED_UP
- [ ] 4.6 实现 `returnWristband()`：USED_UP（或 ACTIVE 先转 USED_UP）→ 手环回 IN_STOCK
- [ ] 4.7 实现 `cancelBound()`：admin 取消 BOUND 绑定 → CANCELLED + 手环回 IN_STOCK
- [ ] 4.8 实现 `extend()`：续杯 = 归还 + 重新发卡（同手环或新手环），事务内完成
- [ ] 4.9 在所有状态变更入口做状态机前置校验，禁止非法跃迁

## 5. 状态机一致性

- [ ] 5.1 把 Wristband.status 与 WristbandBinding.status 的同步收敛到领域服务统一入口，禁止直接 update 单边
- [ ] 5.2 为关键不变量写测试：一个手环同时只能有一条非终态绑定、一个手机号同时只能有一个 ACTIVE 会员、FROZEN 会员的手机号可被复用
- [ ] 5.3 为状态机所有合法跃迁和典型非法跃迁写测试

## 6. 接口与调用方契约

- [ ] 6.1 暂不暴露 HTTP API；先以 `@Service` + 领域事件对外，避免提前固化接口形态（具体 API 留给后续 controller change）
- [ ] 6.2 为 kiosk、admin、桌面端三类调用方定义操作来源类型（issued_by / cancelled_by / ended_by 字段约定）
- [ ] 6.3 把"未充值手环刷卡""非库存手环发卡"等典型错误映射为稳定错误码与文案

## 7. 领域事件与审计

- [ ] 7.1 定义 `BindingEvent`（Issued / DurationToppedUp / Activated / Expired / ManualEnded / Returned / Cancelled / Retired / Extended），事件结构留给 Records 与 Sync 模块消费
- [ ] 7.2 在所有状态变更服务中发布对应事件（事务内或事务后，二选一并记录决策）
- [ ] 7.3 事件载荷包含：实体 ID、前后状态、操作来源、时间戳

## 8. 验证

- [ ] 8.1 单元测试覆盖所有领域服务的成功路径与主要错误路径
- [ ] 8.2 仓库层测试覆盖 UNIQUE 约束（手机号 ACTIVE 唯一、card_uid 唯一、单手环单非终态绑定）
- [ ] 8.3 集成测试覆盖一条完整生命周期：入库 → 发卡 → 充值 → 激活 → 到期 → 归还
- [ ] 8.4 集成测试覆盖续杯（同手环与新手环两条路径）
- [ ] 8.5 集成测试覆盖冻结/解冻与手机号复用
- [ ] 8.6 在 README 或 server/README 中补充"会员+手环域"的最小说明，便于后续模块对接
