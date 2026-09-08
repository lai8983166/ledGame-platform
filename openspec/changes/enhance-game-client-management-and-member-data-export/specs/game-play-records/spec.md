## ADDED Requirements

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
平台 SHALL 对手动停止、启动中止、取消、运行异常或其他非 `NATURAL_` 终止结果保存 `0` 奖励积分，即使请求携带非零关卡积分。

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
平台 SHALL 在结算请求不含 `scoringInput` 时继续使用现有 `raw-score-v1` 兼容策略。

#### Scenario: 旧客户端自然结算
- **WHEN** 旧游戏端只提交自然终止原因和 `rawScore`
- **THEN** 平台继续按 `raw-score-v1` 得到积分并正常关闭 play

#### Scenario: 新客户端不得回退绕过校验
- **WHEN** 请求显式提交 `scoringInput` 但内容无效
- **THEN** 平台拒绝请求
- **AND** 不静默回退到 `raw-score-v1`

