# game-access-entitlement Specification

## Purpose
TBD - created by archiving change add-game-access-records-and-player-query. Update Purpose after archive.
## Requirements
### Requirement: First valid game swipe activates purchased time
The platform SHALL activate a `READY` binding on the first valid game-system wristband request and SHALL use platform-server time as the start of the purchased window.

#### Scenario: Activate a ready wristband
- **WHEN** a game backend submits UID `2283055618` whose open binding is `READY` with a positive purchased duration
- **THEN** the platform changes the binding and wristband to `ACTIVE`, stores `started_at`, and returns the bound member, expiry, and positive remaining seconds

#### Scenario: Activation does not depend on game selection
- **WHEN** a valid wristband is admitted before the player selects a game
- **THEN** the purchased timer starts from that admission and continues even if the preparation UI is later cancelled

### Requirement: Active wristband admission is idempotent
The platform SHALL return the existing active window for an `ACTIVE` binding without resetting or extending its start time.

#### Scenario: Scan an active wristband again
- **WHEN** a game backend submits an `ACTIVE` wristband before its expiry
- **THEN** the platform returns the original `started_at`, the same calculated expiry, and the newly calculated remaining seconds

### Requirement: Ineligible wristbands fail closed
The platform SHALL deny game access for wristbands that are unknown, unbound, expired, associated with an ineligible member, or otherwise not entitled to play, using stable error codes.

#### Scenario: Charged but unbound wristband
- **WHEN** a game backend submits a wristband in `CHARGED`
- **THEN** the platform rejects access with `WRISTBAND_NOT_BOUND` and does not start timing

#### Scenario: Unknown wristband
- **WHEN** a game backend submits a numeric UID that is absent from SQLite
- **THEN** the platform rejects access with `WRISTBAND_NOT_FOUND`

#### Scenario: Expired active window
- **WHEN** an `ACTIVE` binding's calculated expiry is not after platform-server time
- **THEN** the platform changes the binding and wristband to `EXPIRED`, returns `WRISTBAND_EXPIRED`, and reports zero remaining seconds

### Requirement: Remaining balance is server derived
The platform SHALL derive remaining play time from persisted start time and duration and SHALL NOT accept a client-supplied balance or start time as authoritative.

#### Scenario: Client requests current balance
- **WHEN** a caller requests admission or player information for a `READY` or `ACTIVE` binding
- **THEN** the response returns duration, status, server-derived expiry, and remaining seconds calculated from persisted data

