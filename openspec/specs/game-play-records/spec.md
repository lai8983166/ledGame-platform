# game-play-records Specification

## Purpose

以持久化游玩记录关联会员、手环及新旧游戏会话，保证开始和结算幂等，并保留真实积分、终止原因及历史。兼容旧客户端报告的最终积分，不将无法判断的结束原因伪装为自然完成，也不因重试重复发奖。

## Requirements

### Requirement: Starting a game creates one play record
The platform SHALL create one persisted running play record per eligible active member participant when a game backend confirms a selected game. A new game backend SHALL supply its own unique external preparation ID; the legacy compatibility endpoint MAY assign and persist a server-generated session ID using the legacy room IP and start callback.

#### Scenario: Start a game with remaining time
- **WHEN** the game backend submits a unique external preparation ID, device identity, selected game, and an `ACTIVE` binding with positive remaining time
- **THEN** the platform creates one `RUNNING` play record linked to the member and binding and returns its platform play ID

#### Scenario: Start a legacy game
- **WHEN** the compatibility endpoint receives a valid legacy start callback with eligible participant UIDs and no conflicting running session for that room
- **THEN** the platform creates one `RUNNING` record per member, linked by one persisted server-generated session ID

#### Scenario: Start after expiry
- **WHEN** the game backend attempts to create a play after the binding window has expired
- **THEN** the platform rejects the start and does not create a play record

### Requirement: Play start is idempotent
The platform SHALL use the game device and external preparation ID as an idempotency key for the new protocol. For a legacy start callback, it SHALL treat a repeat with the same room, game, and ordered participant list as the same start while that session remains running.

#### Scenario: Retry a timed-out start request
- **WHEN** the same game device resubmits the same external preparation ID
- **THEN** the platform returns the existing play record rather than inserting another record

#### Scenario: Repeat a legacy start callback
- **WHEN** the same legacy room repeats the same game and ordered participant list while its session is running
- **THEN** the platform returns/acknowledges the existing session without inserting duplicate play records

### Requirement: One wristband cannot run concurrent games
The platform SHALL prevent more than one open running play for the same binding.

#### Scenario: Another device attempts concurrent use
- **WHEN** a different external session tries to start while the binding already has a `RUNNING` play
- **THEN** the platform rejects the request with `WRISTBAND_IN_USE`

### Requirement: Game result settlement is idempotent
The platform SHALL persist each terminal result at most once per play. New-protocol settlements SHALL retain their natural-completion or abort semantics and derive awarded points from the configured scoring policy. Legacy settlements SHALL store the old client's final reported points as authoritative, use a distinct legacy-settled status and scoring-policy marker, and leave success and termination cause unknown when the old protocol does not provide them.

#### Scenario: Settle a natural game completion
- **WHEN** the game backend submits a success result for a running play
- **THEN** the platform marks the play completed and stores its score and awarded points

#### Scenario: Repeat the same result
- **WHEN** callback retry submits a result for an already settled play
- **THEN** the platform returns the stored settlement without adding points a second time

#### Scenario: Settle a stopped game
- **WHEN** the game backend reports a manual stop, startup abort, or runtime failure
- **THEN** the platform closes the play with the supplied termination reason and preserves any supplied diagnostic result data

#### Scenario: Settle a legacy final score
- **WHEN** a matching legacy end callback reports a final score for a participant in the room's persisted open session
- **THEN** the platform stores the exact reported non-negative points as a legacy-settled result without claiming natural completion or applying the new scoring policy

#### Scenario: Legacy termination cannot be determined
- **WHEN** a legacy end callback is received without any termination-reason field
- **THEN** the platform records its termination reason as unknown instead of inferring success or failure

#### Scenario: Legacy end callback is duplicated
- **WHEN** an identical end callback is received after its legacy session has already been settled
- **THEN** the platform acknowledges the duplicate without adding points again

### Requirement: A play settlement does not end purchased access
The platform SHALL keep the enclosing binding `ACTIVE` after a game finishes when its purchased window has not expired.

#### Scenario: Start another game in the same window
- **WHEN** one play is settled and the binding still has positive remaining time
- **THEN** the member can create another play record using the same binding

### Requirement: 平台按结构化关卡积分依据结算
平台 SHALL 对新版游戏端提交的 `scoringInput` 执行 `level-clear-points-v1` 策略，并把玩法 `rawScore` 与最终 `points_awarded` 分别保存。

#### Scenario: 自然终止且积分依据可发放
- **WHEN** 运行中的 play 以 `NATURAL_` 终止原因结算，且 `scoringInput` 的版本受支持、`awardEligible=true`、累计关卡积分有效
- **THEN** 平台以累计关卡积分作为 `points_awarded`
- **AND** 保存 `scoring_policy=level-clear-points-v1`
- **AND** 在结果审计数据中保留逐关依据

#### Scenario: 原始分数与奖励积分不同
- **WHEN** 游戏端提交 `rawScore=120` 且有效累计关卡积分为 `30`
- **THEN** 游玩记录分别保存原始分数 `120` 和奖励积分 `30`
- **AND** 排行榜与会员积分总额只累计 `30`

#### Scenario: 自然失败但已有通关关卡
- **WHEN** play 以受支持的自然失败原因结束且积分依据中包含此前实际通关关卡
- **THEN** 平台按有效累计关卡积分结算
- **AND** 未通关关卡不产生积分

### Requirement: 非自然终止不得发放关卡积分
平台 SHALL 对新协议提交的手动停止、启动中止、取消、运行异常或其他非 `NATURAL_` 终止结果保存 `0` 奖励积分，即使请求携带非零关卡积分。固定旧协议没有终止原因的回调仍按旧协议报告积分规则处理，不凭缺失原因推断自然或非自然结束。

#### Scenario: 手动停止携带非零依据
- **WHEN** `MANUAL_STOP` 结果携带非零累计关卡积分
- **THEN** 平台保存 `points_awarded=0`
- **AND** 记录策略决定与诊断依据但不增加会员积分

#### Scenario: 客户端错误标记可发放
- **WHEN** 非自然终止请求把 `awardEligible` 标记为 `true`
- **THEN** 平台以终止原因优先并发放 `0` 积分

### Requirement: 平台验证积分依据
平台 SHALL 拒绝结构损坏、版本不支持、累计值与逐关明细不一致、负数、小数或超过上限的 `scoringInput`，且不部分结算 play。

#### Scenario: 明细合计不一致
- **WHEN** `scoringInput.totalPoints` 与获奖关卡明细之和不一致
- **THEN** 平台返回明确 `400` 错误
- **AND** play 保持 `RUNNING` 以允许同一结果修正后重试

#### Scenario: 重复提交有效结果
- **WHEN** 同一 play 的有效结果因超时被重复提交
- **THEN** 平台返回第一次提交保存的原始分数、奖励积分和策略
- **AND** 会员积分只增加一次

### Requirement: 旧版游戏端保持兼容
平台 SHALL 在新协议结算请求不含 `scoringInput` 时继续使用现有 `raw-score-v1` 兼容策略。固定旧协议回调仍按 legacy-game-client-compatibility 的报告积分语义结算，不改用新协议策略。

#### Scenario: 旧客户端自然结算
- **WHEN** 使用新协议的旧版游戏端只提交自然终止原因和 `rawScore`
- **THEN** 平台继续按 `raw-score-v1` 得到积分并正常关闭 play

#### Scenario: 新客户端不得回退绕过校验
- **WHEN** 请求显式提交 `scoringInput` 但内容无效
- **THEN** 平台拒绝请求
- **AND** 不静默回退到 `raw-score-v1`
