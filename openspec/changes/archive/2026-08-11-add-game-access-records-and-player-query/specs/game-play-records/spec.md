## ADDED Requirements

### Requirement: Starting a game creates one play record
The platform SHALL create a persisted running play record for an eligible active binding when the game backend confirms a selected game.

#### Scenario: Start a game with remaining time
- **WHEN** the game backend submits a unique external preparation ID, device identity, selected game, and an `ACTIVE` binding with positive remaining time
- **THEN** the platform creates one `RUNNING` play record linked to the member and binding and returns its platform play ID

#### Scenario: Start after expiry
- **WHEN** the game backend attempts to create a play after the binding window has expired
- **THEN** the platform rejects the start and does not create a play record

### Requirement: Play start is idempotent
The platform SHALL use the game device and external preparation ID as an idempotency key.

#### Scenario: Retry a timed-out start request
- **WHEN** the same game device resubmits the same external preparation ID
- **THEN** the platform returns the existing play record rather than inserting another record

### Requirement: One wristband cannot run concurrent games
The platform SHALL prevent more than one open running play for the same binding.

#### Scenario: Another device attempts concurrent use
- **WHEN** a different external session tries to start while the binding already has a `RUNNING` play
- **THEN** the platform rejects the request with `WRISTBAND_IN_USE`

### Requirement: Game result settlement is idempotent
The platform SHALL persist the terminal result, raw score, awarded points, result payload, and end time at most once per play.

#### Scenario: Settle a natural game completion
- **WHEN** the game backend submits a success result for a running play
- **THEN** the platform marks the play completed and stores its score and awarded points

#### Scenario: Repeat the same result
- **WHEN** callback retry submits a result for an already settled play
- **THEN** the platform returns the stored settlement without adding points a second time

#### Scenario: Settle a stopped game
- **WHEN** the game backend reports a manual stop, startup abort, or runtime failure
- **THEN** the platform closes the play with the supplied termination reason and preserves any supplied diagnostic result data

### Requirement: A play settlement does not end purchased access
The platform SHALL keep the enclosing binding `ACTIVE` after a game finishes when its purchased window has not expired.

#### Scenario: Start another game in the same window
- **WHEN** one play is settled and the binding still has positive remaining time
- **THEN** the member can create another play record using the same binding
