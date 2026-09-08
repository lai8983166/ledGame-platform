## Context

`game_play_records` 当前以 `(device_id, external_session_id)` 唯一，每次 start 只接受一个 UID，因此同一游戏 session 无法关联多位会员。现有积分策略以单条 play 的自然完成状态和 `rawScore` 计算 `points_awarded`，单 play result 已具备幂等性。

目标是允许一局共享游戏拥有多条参与者 play。游戏端仍发送一份共享结果，但会员平台必须为每位会员保存、查询和积分一次，并保证多人启动不会产生部分 `RUNNING` 记录。

## Goals / Non-Goals

**Goals:**

- 原子验证并创建同一 external session 下的多条参与者 play。
- 对批量启动与逐 play 结算提供确定幂等性。
- 保持同一 binding 不可并发游玩和现有单人 API 兼容。
- 让每位会员的 Player Info、积分和排名自然包含自己的多人记录。
- 使用真实 SQLite 和跨服务测试覆盖三个 Simple 变体。

**Non-Goals:**

- 不保存个人地砖事件或个人游戏内分数。
- 不新增团队榜、组队关系或 Debug Panel/副屏数据。
- 不改变当前积分公式；同一共享 `rawScore` 对每位参与者独立应用同一策略。

## Decisions

### 1. 一局多条 play，共享 externalSessionId

保留 `game_play_records` 作为每位会员的事实记录，为记录增加 `participant_index`，并把唯一键改为 `(device_id, external_session_id, binding_id)`，另以 `(device_id, external_session_id, participant_index)` 保证槽位稳定。多条记录共享 game、room、external session，但各自拥有 member、binding、UID、status、points 和 result。

相比新增独立 game session/group 表，这一方案复用现有 Player Info、积分汇总和结算模型，适合当前只需要共享结果的 MVP。

### 2. 新增原子 batch start，单人 start 复用公共逻辑

新增 `POST /api/game-plays/start-batch`，请求携带 device、room、externalSessionId、game 和有序 UID 列表。事务内先验证 UID/member/binding 均不重复、全部访问窗口有效且没有其他 RUNNING play，再一次性插入所有记录。现有单人 start 通过一项 batch 逻辑保持兼容。

### 3. 批量幂等键包含完整参与者集合

同一 `(device, externalSessionId)` 重试时，平台读取已有 records，只有 UID/binding 集合及顺序与请求完全一致才返回既有列表；集合冲突返回稳定错误，不增删已有记录。这样游戏后端可以安全重试超时请求。

### 4. 结算继续按 playId 单独执行

不新增批量 result API。游戏后端使用每个 `playId` 调用既有 result endpoint；`GamePlayService.settle` 的幂等规则继续确保每条记录最多加分一次。所有参与者接收相同 `rawScore`，但每条 play 独立保存 `points_awarded`。

### 5. 迁移由 schema 与显式 migration 双重保护

新安装直接创建新列和索引；已有 SQLite 由 `PlatformSchemaMigration` 增加 `participant_index`、按既有单人数据填充 0、删除旧 external-session 唯一索引并创建新索引。迁移前后均运行 schema integration tests。

### 6. 测试先于实现

先写 batch 原子性、重试集合一致性、重复 member/binding、并发占用、N 条相同积分和单人兼容失败测试；再更新大型验收，使 2 人分别刷卡并在 `simple`、`normal`、`diffcult` 中共享结果且各有一条记录。

## Risks / Trade-offs

- [批量请求超时但事务已提交] → 完整 participant set 幂等重试返回原记录。
- [历史 SQLite 唯一索引阻止多人插入] → migration 必须显式删除旧索引并在启动测试中验证新结构。
- [某位玩家结算延迟] → 每条 play 独立幂等；其他会员积分可先完成，失败项由游戏后端 outbox 重试。
- [同一会员用多只手环刷分] → batch 同时校验 memberId 唯一和 binding/UID 唯一。

## Migration Plan

1. 增加 migration 测试和 batch API contract 测试。
2. 迁移 schema/index，并实现事务性 batch start；保留单人 endpoint。
3. 验证 Player Info、积分排名和列表无需结构性改写即可读取多条 records。
4. 更新跨服务 acceptance 并与游戏后端同步发布。
5. 回滚应用版本不会删除新记录；旧版本只能读取它们，但不能为同一 session 新建多人记录，因此生产回滚前应停止新局。

## Open Questions

无。首版同一会员在同一局只能出现一次，每位参与者获得相同共享结果对应的积分。
