## ADDED Requirements

### Requirement: 多人启动必须全部满足访问资格
平台 SHALL 在创建多人 play 前验证全部参与者的手环、binding、会员和剩余时间，并 SHALL 在任一参与者不合格时拒绝整批启动。

#### Scenario: 全部参与者有效
- **WHEN** 请求中的每只手环均为互不重复会员的 `ACTIVE` binding 且剩余时间为正
- **THEN** 平台 SHALL 允许批量创建每位参与者的 play

#### Scenario: 任一参与者已过期
- **WHEN** 多人请求中至少一只手环已经到期
- **THEN** 平台 SHALL 返回稳定的过期错误
- **AND** SHALL NOT 为其他参与者创建部分 play

### Requirement: 同局参与者必须唯一
平台 SHALL 要求同一 external session 中的 UID、binding 和 memberId 均互不重复，防止一位会员通过多只手环重复获得积分。

#### Scenario: 重复 UID 或 binding
- **WHEN** 批量请求重复提交同一 UID 或映射到同一 binding
- **THEN** 平台 SHALL 拒绝整批请求且不创建记录

#### Scenario: 不同手环属于同一会员
- **WHEN** 两个不同 UID 映射到同一 memberId
- **THEN** 平台 SHALL 返回稳定的重复会员错误
- **AND** 该会员 SHALL NOT 在同一局获得多条 play
