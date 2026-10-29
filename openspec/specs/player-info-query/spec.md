# player-info-query Specification

## Purpose

通过手机号或手环只读查询会员资料、账户与本次用卡余额、排名积分、剩余积分、等级和最近游玩记录。自助端按真实服务端数据展示，查询不创建会员、不绑定手环，也不重置已开始的用卡计时。

## Requirements

### Requirement: Player points and rank are derived from settled games
The platform SHALL calculate total points from settled play records, including completed new-protocol records and legacy-settled records with reported points, and SHALL rank active members using that same persisted point source. Running, aborted, or otherwise unsettled plays SHALL NOT increase totals.

#### Scenario: Two settled games contribute to points
- **WHEN** a member has two settled plays with awarded points
- **THEN** Player Info returns the sum once for each play and a rank consistent with all active members' totals

#### Scenario: Legacy reported points contribute to totals
- **WHEN** a member has a legacy-settled play with points reported by the legacy game client
- **THEN** Player Info and the member leaderboard include those exact points once, even though the old client did not report a termination reason

#### Scenario: Running or aborted play has no awarded points
- **WHEN** a play is running, aborted, or has no accepted settlement
- **THEN** it does not increase the member's total or ranking value

### Requirement: Player balance reflects current server time
The platform SHALL return unallocated member balance even without a wristband; wristband fields SHALL separately report its allocated remaining seconds and expiry. Successful self-service binding SHALL start continuous allocated timing immediately.

#### Scenario: Query an active wristband
- **WHEN** Player Info includes an ACTIVE wristband
- **THEN** it returns the UID and existing access fields with server-derived allocated remaining time separately from account balance

#### Scenario: Query a ready wristband
- **WHEN** Player Info includes a READY wristband
- **THEN** the member's preserved balance is available and consumption has not resumed yet

#### Scenario: Query without a wristband
- **WHEN** the member has returned the wristband and retains time
- **THEN** the response exposes the paused account balance independently of the wristband list

### Requirement: Kiosk query does not mutate identity or access
The kiosk SHALL present Player Info as a read-only flow and SHALL clear personal query state when returning home.

#### Scenario: View and leave Player Info
- **WHEN** a customer queries a member and returns to the kiosk home screen
- **THEN** no member, binding, balance, or play record is changed and the displayed personal data is cleared from the UI session

### Requirement: Player information is queried by phone or wristband
The platform SHALL return an active member's persisted registration information, points, rank, current wristbands, balances, and recent games from one read-only player-information endpoint. The kiosk MAY identify the member by a normalized phone number or by a wristband UID; exactly one identifier MUST be supplied for a query.

#### Scenario: Query an existing member by phone
- **WHEN** the kiosk submits the phone number of an active member
- **THEN** the response contains the member profile, total awarded points, rank, current wristband entries, and recent play records

#### Scenario: Query an existing member by wristband
- **WHEN** the kiosk submits the UID of a wristband currently bound to an active member
- **THEN** the response contains the same member profile, points, rank, wristband balances, and recent play records as a phone query

#### Scenario: Query an unknown identifier
- **WHEN** the kiosk submits a valid phone number or wristband UID that has no active member
- **THEN** the platform returns a stable not-found result without creating a member or changing the wristband

#### Scenario: Reject an ambiguous or invalid query
- **WHEN** the request omits both identifiers, supplies both identifiers, or supplies an invalid identifier format
- **THEN** the platform returns a stable validation error without querying or mutating member data

### Requirement: Player information exposes ranking and play history
The kiosk player-information result SHALL expose the member's current total points and rank together with a bounded list of recent play records, including game name, status, time, score, and awarded points when available.

#### Scenario: Member has settled and recent games
- **WHEN** a queried member has settled games and recent play records
- **THEN** the result shows the rank and total points and lists those recent games without fabricating missing values

#### Scenario: Member has no games
- **WHEN** a queried member has no play records
- **THEN** the result shows zero or the server-defined empty rank value and an explicit empty-history state

### Requirement: Player information distinguishes available and ranking points
Player Info SHALL expose remaining redeemable points separately from cumulative ranking points. The existing total and rank fields SHALL retain their settled-game meaning. The kiosk SHALL show both values without providing a deduction operation.

#### Scenario: Query after redemption
- **WHEN** a member has earned 10000 points and redeemed 5000
- **THEN** phone and wristband queries return remaining 5000, ranking and legacy total 10000, and the unchanged rank

#### Scenario: Compatibility with an older response
- **WHEN** the kiosk receives only the original total and rank fields
- **THEN** it can still render both point displays using total as the fallback without failing the query

### Requirement: Player information includes ranking-based level
The platform SHALL return the member's current level name derived from ranking points and current level ranges. Kiosk information remains read-only and displays a localized unrated label when no range matches or an older response omits the field.

#### Scenario: Redemption preserves level
- **WHEN** a member redeems points and queries by phone or wristband
- **THEN** remaining points decrease but the level continues to match ranking points without changing any existing point fields

### Requirement: 手环查询直接等待扫描
自助端 SHALL 在切换手环查询时立即显示刷卡等待，不要求开始按钮、输入框焦点或软键盘；接收数字加回车后调用只读查询。取消返回手机号查询，失败可继续刷卡，超时或离开后迟到响应不得展示旧资料。

#### Scenario: 手环查询与取消
- **WHEN** 顾客从手机号切换到手环查询并取消，随后重新切换并刷手环
- **THEN** 每次切换均直接等待，取消不查询或绑定，成功仅展示对应会员信息，不改变权益和计时
