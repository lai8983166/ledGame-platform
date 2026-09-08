## MODIFIED Requirements

### Requirement: Starting a game creates one play record
当游戏后端确认所选游戏时，平台 SHALL 为每个符合资格的参与者 binding 创建一条持久化的运行中 play 记录；多条记录可以共享同一个 device 和 external session。

#### Scenario: 启动仍有可用时间的单人游戏
- **WHEN** 游戏后端提交唯一 external preparation ID、device identity、所选 game 以及一个剩余时间为正的 `ACTIVE` binding
- **THEN** 平台 SHALL 创建一条关联 member 与 binding 的 `RUNNING` play 记录，并返回 platform play ID

#### Scenario: 启动多人游戏
- **WHEN** 游戏后端为一个 external preparation ID 提交一组有序、符合资格且互不重复的参与者 binding
- **THEN** 平台 SHALL 在同一事务中为每位参与者各创建一条 `RUNNING` play
- **AND** 所有记录 SHALL 共享 device、room、external session 和所选 game

#### Scenario: 任一参与者不符合资格
- **WHEN** 任一参与者 binding 已过期、不可用、重复或正在运行其他游戏
- **THEN** 平台 SHALL 拒绝整批请求
- **AND** 平台 SHALL NOT 为该请求创建部分 `RUNNING` 记录

### Requirement: Play start is idempotent
平台 SHALL 使用 game device、external preparation ID 和完整参与者集合构成多人启动的幂等身份，同时保持单人重试行为。

#### Scenario: 重试相同的多人启动请求
- **WHEN** 同一 game device 以相同 external preparation ID 和相同有序参与者 binding 再次提交请求
- **THEN** 平台 SHALL 按参与者顺序返回既有 play records，而不是插入重复记录

#### Scenario: 以冲突的参与者集合重试
- **WHEN** 已存在的 device 和 external preparation ID 被使用不同参与者 UID、binding 或顺序再次提交
- **THEN** 平台 SHALL 以稳定的幂等冲突拒绝请求
- **AND** 既有 play records SHALL 保持不变

### Requirement: Game result settlement is idempotent
平台 SHALL 对每条参与者 play 至多持久化一次终态结果、raw score、awarded points、result payload 和 end time，包括多条 play 共享一个 external session 的情况。

#### Scenario: 结算共享的多人自然完成结果
- **WHEN** 游戏后端向每条运行中的参与者 play 提交相同的自然完成状态和 raw score
- **THEN** 平台 SHALL 独立地将每条 play 标记为已完成
- **AND** 每位会员 SHALL 按现有计分策略获得相同的积分判定

#### Scenario: 重复提交一位参与者的结果
- **WHEN** 回调重试为已经结算的参与者 play 再次提交结果
- **THEN** 平台 SHALL 返回已保存的结算，且不会第二次增加该会员积分
- **AND** 其他参与者记录 SHALL 保持不变

#### Scenario: 结算被中止的多人游戏
- **WHEN** 游戏后端为每条参与者 play 报告 manual stop、startup abort 或 runtime failure
- **THEN** 平台 SHALL 使用所提供的 termination reason 关闭每条 play，并保留已提供的诊断 result 数据
