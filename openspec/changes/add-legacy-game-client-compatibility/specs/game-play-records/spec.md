## MODIFIED Requirements

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
