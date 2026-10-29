## MODIFIED Requirements

### Requirement: First valid game swipe activates purchased time
The platform SHALL validate a `READY` binding on a valid game-system wristband request without consuming its purchased gameplay balance, and SHALL reserve actual consumption for a platform play that reaches game-engine `RUNNING`.

#### Scenario: Activate a ready wristband
- **WHEN** a game backend submits UID `2283055618` whose open binding is `READY` with a positive remaining gameplay balance
- **THEN** the platform returns the bound member, binding, purchased duration, and positive remaining seconds
- **AND** the platform does not reduce the balance or start a wall-clock expiry window

#### Scenario: Activation does not depend on game selection
- **WHEN** a valid wristband is admitted before game selection and the preparation UI is later cancelled
- **THEN** the binding remains `READY` with the same remaining gameplay balance

### Requirement: Active wristband admission is idempotent
The platform SHALL treat `ACTIVE` as an in-game occupancy state and SHALL return the existing running play context without starting another timer or extending the available balance.

#### Scenario: Scan an active wristband again
- **WHEN** a game backend submits an `ACTIVE` wristband that already has a `RUNNING` play
- **THEN** the platform rejects a new play with `WRISTBAND_IN_USE` or returns the same idempotent play for the same external session
- **AND** the request does not deduct balance

### Requirement: Ineligible wristbands fail closed
The platform SHALL deny game access for wristbands that are unknown, unbound, have zero remaining gameplay balance, are associated with an ineligible member, or are otherwise not entitled to play, using stable error codes.

#### Scenario: Charged but unbound wristband
- **WHEN** a game backend submits a wristband in `CHARGED`
- **THEN** the platform rejects access with `WRISTBAND_NOT_BOUND` and does not consume time

#### Scenario: Unknown wristband
- **WHEN** a game backend submits a numeric UID that is absent from SQLite
- **THEN** the platform rejects access with `WRISTBAND_NOT_FOUND`

#### Scenario: Expired active window
- **WHEN** a `READY` binding has zero remaining gameplay seconds
- **THEN** the platform changes the binding and wristband to `EXPIRED`, returns `WRISTBAND_EXPIRED`, and reports zero remaining seconds

### Requirement: Remaining balance is server derived
The platform SHALL persist remaining gameplay seconds and derive the authoritative balance from purchased time minus idempotently settled `RUNNING` consumption, and SHALL NOT accept a client-supplied balance as authoritative.

#### Scenario: Client requests current balance
- **WHEN** a caller requests admission or player information for a `READY` or `ACTIVE` binding
- **THEN** the response returns purchased duration, status, and authoritative remaining gameplay seconds
- **AND** an expiry timestamp based on first scan is not used as the balance source
