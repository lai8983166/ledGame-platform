## MODIFIED Requirements

### Requirement: Starting a game creates one play record
The platform SHALL create a persisted running play record for an eligible ready binding when the game backend confirms a selected game, and SHALL change that binding to `ACTIVE` without deducting time before actual `RUNNING` usage is reported.

#### Scenario: Start a game with remaining time
- **WHEN** the game backend submits a unique external preparation ID, device identity, selected game, and a `READY` binding with positive remaining gameplay seconds
- **THEN** the platform creates one `RUNNING` play record linked to the member and binding, changes the binding to `ACTIVE`, and returns its platform play ID and available seconds

#### Scenario: Start after expiry
- **WHEN** the game backend attempts to create a play for a binding with zero remaining gameplay seconds
- **THEN** the platform rejects the start and does not create a play record

### Requirement: Game result settlement is idempotent
The platform SHALL persist the terminal result, raw score, awarded points, result payload, actual accumulated `RUNNING` duration, and end time at most once per play, and SHALL deduct that duration from the linked binding at most once.

#### Scenario: Settle a natural game completion
- **WHEN** the game backend submits a success result and a non-negative accumulated `RUNNING` duration for a running play
- **THEN** the platform marks the play completed, stores its score and awarded points, and deducts no more than the binding's available gameplay balance

#### Scenario: Repeat the same result
- **WHEN** callback retry submits a result for an already settled play
- **THEN** the platform returns the stored settlement without adding points or deducting gameplay balance a second time

#### Scenario: Settle a stopped game
- **WHEN** the game backend reports a manual stop, runtime failure, or balance exhaustion after the game entered `RUNNING`
- **THEN** the platform closes the play, preserves diagnostic result data, and deducts the reported accumulated `RUNNING` duration

#### Scenario: Settle a startup abort
- **WHEN** the game backend reports `STARTUP_ABORT` for a play that never entered `RUNNING`
- **THEN** the platform closes the play with zero consumed seconds and does not reduce the binding balance

### Requirement: A play settlement does not end purchased access
The platform SHALL return the binding to `READY` after settlement when gameplay balance remains, and SHALL change it to `EXPIRED` only when remaining gameplay seconds reach zero.

#### Scenario: Start another game in the same window
- **WHEN** one play is settled and the binding still has positive remaining gameplay seconds
- **THEN** the binding becomes `READY` and the member can create another play using the same binding

#### Scenario: Settlement exhausts balance
- **WHEN** the settled `RUNNING` duration consumes all remaining gameplay seconds
- **THEN** the binding and wristband become `EXPIRED` and further game access is denied

## ADDED Requirements

### Requirement: Multiplayer settlement deducts every participant consistently
The platform SHALL settle every participant play in one game session with the same authoritative accumulated `RUNNING` duration while preserving per-play idempotency.

#### Scenario: Settle a two-player game
- **WHEN** one game session ends after accumulating 75 `RUNNING` seconds for two bound wristbands
- **THEN** each participant play stores 75 consumed seconds and each binding loses 75 seconds exactly once

#### Scenario: One participant result is retried
- **WHEN** a previously settled participant play is submitted again while another participant is being settled
- **THEN** the retry does not cause either binding to be deducted more than once
