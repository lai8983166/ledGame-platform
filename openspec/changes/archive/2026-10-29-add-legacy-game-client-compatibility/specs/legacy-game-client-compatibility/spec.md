## Purpose

使旧版游戏端无需改版即可通过其固定 HTTP 协议连接新版会员管理端，让刷卡校验、多人游戏记录、最终积分与房间在线状态进入新版统一数据，同时如实标明旧协议无法提供的信息。

## ADDED Requirements

### Requirement: Legacy fixed HTTP endpoints remain compatible
The platform SHALL accept the legacy game's `POST /dev/gameCallback` commands `1`, `2`, `3`, and `5`, and `GET /dev/ping` on TCP port `16668`, while keeping the new platform API available on its configured port. The compatibility endpoints SHALL return JSON in the response shape the legacy client expects.

#### Scenario: Legacy client checks connectivity
- **WHEN** the legacy client sends `GET /dev/ping` to port `16668`
- **THEN** the platform returns JSON whose `data` field is the string `pong`

#### Scenario: Unknown command is rejected without affecting data
- **WHEN** a request to `/dev/gameCallback` contains an unsupported or missing `cmd`
- **THEN** the platform returns a structured non-success JSON response and creates or changes no member, access, play, or room business record

### Requirement: Legacy batch validation returns legacy tokens
For `cmd=5`, the platform SHALL validate every supplied wristband against the existing member and wristband eligibility rules, activate valid `READY` bindings using server time, and return one legacy token entry per supplied wristband in input order. The response SHALL have `code`, `msg`, and `data`; each `data` item SHALL be an array whose second element is a token containing at least `ic`, `type`, `endTime`, and `durationMinutes`. Validation failure SHALL return a non-200 business code and SHALL NOT partially activate the batch. The legacy `isAdmin` Boolean SHALL be retained as session metadata and SHALL NOT independently bypass member or wristband eligibility.

#### Scenario: Valid ready wristbands are admitted as a batch
- **WHEN** `cmd=5` supplies one or more distinct wristband UIDs that are all bound to active members and have `READY` or unexpired `ACTIVE` access
- **THEN** all `READY` bindings are activated at the platform time and the response contains ordered `type=2` tokens with each UID's actual expiry and purchased duration

#### Scenario: One invalid wristband rejects the whole batch
- **WHEN** `cmd=5` supplies a batch containing an unknown, unbound, expired, frozen-member, duplicate, or otherwise ineligible wristband
- **THEN** the response has a non-200 business code and no other `READY` wristband in that batch is activated

#### Scenario: Active wristband is revalidated without restarting its timer
- **WHEN** `cmd=5` supplies a wristband whose binding is already `ACTIVE` and unexpired
- **THEN** the returned token reflects the existing server-derived expiry and does not reset or extend the timer

#### Scenario: Administrator flag does not replace wristband eligibility
- **WHEN** `cmd=5` includes `isAdmin=true` and any supplied wristband is otherwise unknown or ineligible
- **THEN** the batch is rejected under the same eligibility rules and no access is granted solely because of the flag

### Requirement: Legacy heartbeat provides IP-based room presence
The platform SHALL treat the source IP of a legacy `cmd=1` request as the room identity, mark that room online, and expose it through the existing room list. A room without another heartbeat for 60 seconds SHALL be shown offline. The platform SHALL NOT infer controller, floor, queue, or real-time score health from this heartbeat.

#### Scenario: Legacy game terminal heartbeats
- **WHEN** a terminal sends `{"cmd":1}` to `/dev/gameCallback`
- **THEN** its source-IP room is shown online and its last-seen time is updated

#### Scenario: Legacy heartbeat expires
- **WHEN** a legacy room has received no heartbeat for 60 seconds
- **THEN** the room is shown offline without deleting its saved play or point records

#### Scenario: Multiple legacy rooms use different source IPs
- **WHEN** two legacy terminals with distinct source IPs send heartbeats
- **THEN** the room list exposes two independently identified rooms

### Requirement: Legacy game start creates member play records
For `cmd=2`, the platform SHALL use the sending room IP, game ID/name, and supplied participant UIDs to associate one running play record with each valid member participant. The platform SHALL persist the association so a later `cmd=3` can settle it, and SHALL expose the active game and known participants in the room projection. It SHALL NOT create synthetic members for `mock`, missing, or unknown UIDs.

#### Scenario: Start a multiplayer legacy game
- **WHEN** `cmd=2` reports a game and multiple UIDs that passed admission and have no conflicting running play
- **THEN** the platform creates one running play per member, associates them to one internal session, and reports the game and participants for that room

#### Scenario: Duplicate start while the same legacy session is running
- **WHEN** the same room repeats `cmd=2` with the same game and ordered participants while that session is still running
- **THEN** the platform acknowledges the existing session without creating duplicate play records

#### Scenario: Start cannot be associated with eligible members
- **WHEN** `cmd=2` contains participants that cannot be matched to the admitted active member wristbands
- **THEN** the platform returns a structured rejection, creates no synthetic member or awarded points, and records a diagnostic without exposing raw wristband values in logs

### Requirement: Legacy final points are settled once and retained as reported
For `cmd=3`, the platform SHALL associate each reported `{ic, points}` with the matching participant in the current legacy session, persist the reported non-negative 64-bit point value without recalculating it through the new game scoring policy, and include it in the member's total points and rankings. Because the legacy message does not identify why the game stopped, the platform SHALL represent this as a distinct legacy-settled outcome with unknown termination reason, not as a proven natural completion. A repeated identical settlement for an already settled session SHALL NOT award points twice. The platform SHALL check a previously accepted exact callback fingerprint before matching the callback to a currently open session for the same room and game. Since the legacy payload has no session identifier, a genuinely new game that produces the exact same settlement payload for that room and game cannot be distinguished from a replay and SHALL be acknowledged as a duplicate rather than risk settling the wrong session.

#### Scenario: Settle reported points for every multiplayer participant
- **WHEN** `cmd=3` reports exactly one valid final point value for every participant in the current session
- **THEN** each participant's record stores that exact value with a legacy-reported scoring marker, the member totals and rankings include the values, and the session is closed with its termination reason marked unknown

#### Scenario: Repeat a previously accepted settlement
- **WHEN** the same settlement is delivered again for a session already settled
- **THEN** the platform acknowledges it without creating another settlement or increasing member totals again

#### Scenario: An old exact replay arrives after another session has started
- **WHEN** an exact callback previously settled for a room and game is replayed while another session for the same room and game is currently running
- **THEN** the platform acknowledges the replay as a duplicate and leaves the running session unchanged

#### Scenario: Settlement does not match the open session
- **WHEN** `cmd=3` contains a different game, participant set, duplicate participant, missing score, or negative score than the open session
- **THEN** the platform does not assign any point to a different member and returns a structured diagnostic response

#### Scenario: A room ends with no active correlated session
- **WHEN** `cmd=3` arrives for a room with no uniquely matchable open session
- **THEN** the platform does not guess which play records to settle and does not award points

### Requirement: Legacy room projection reports only known state
The platform SHALL clear the legacy room's active-game projection after a matching settlement and SHALL present its online presence separately from game or hardware health. It SHALL retain the room record and settled play history when the terminal disconnects or the server restarts.

#### Scenario: Legacy game ends
- **WHEN** a `cmd=3` settlement is accepted for the room's current session
- **THEN** the room no longer shows that session as running while its completed legacy-settlement history remains available in member records

#### Scenario: Server restarts during a legacy game
- **WHEN** the platform restarts while a persisted legacy session is still running
- **THEN** the session-to-play association remains available for a later matching `cmd=3`, while the room is shown offline until another heartbeat arrives
