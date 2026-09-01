## MODIFIED Requirements

### Requirement: Player balance reflects current server time
The platform SHALL return each wristband's authoritative persisted remaining gameplay seconds and current occupancy status without subtracting time merely because wall-clock time has passed.

#### Scenario: Query an active wristband
- **WHEN** Player Info includes an `ACTIVE` wristband with an open play
- **THEN** it returns the UID, purchased duration, committed remaining seconds, and an indication that the current game is still awaiting final usage settlement

#### Scenario: Query a ready wristband
- **WHEN** Player Info includes a `READY` wristband
- **THEN** it returns the persisted remaining gameplay seconds and indicates that no gameplay time is currently being consumed

#### Scenario: Query after game settlement
- **WHEN** a game result with accumulated `RUNNING` duration has been settled
- **THEN** the next Player Info query returns the reduced persisted balance
