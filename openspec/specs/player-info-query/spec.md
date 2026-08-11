# player-info-query Specification

## Purpose
TBD - created by archiving change add-game-access-records-and-player-query. Update Purpose after archive.
## Requirements
### Requirement: Player information is queried by phone
The platform SHALL return an active member's persisted registration information, points, rank, current wristbands, balances, and recent games from one player-information endpoint.

#### Scenario: Query an existing member
- **WHEN** the kiosk submits the phone number of an active member
- **THEN** the response contains the member profile, total awarded points, rank, current wristband entries, and recent play records

#### Scenario: Query an unknown phone
- **WHEN** the kiosk submits a valid phone number with no active member
- **THEN** the platform returns a stable not-found result without creating a member

### Requirement: Player points and rank are derived from settled games
The platform SHALL calculate total points from settled play records and SHALL rank active members using the same persisted point source.

#### Scenario: Two settled games contribute to points
- **WHEN** a member has two settled plays with awarded points
- **THEN** Player Info returns the sum once for each play and a rank consistent with all active members' totals

#### Scenario: Running or aborted play has no awarded points
- **WHEN** a play has not been successfully settled with awarded points
- **THEN** it does not increase the member's total or ranking value

### Requirement: Player balance reflects current server time
The platform SHALL calculate every returned wristband balance at query time.

#### Scenario: Query an active wristband
- **WHEN** Player Info includes an `ACTIVE` wristband before expiry
- **THEN** it returns the UID, status, purchased duration, start, expiry, and current remaining seconds

#### Scenario: Query a ready wristband
- **WHEN** Player Info includes a `READY` wristband
- **THEN** it returns the full purchased duration as available and indicates that timing has not started

### Requirement: Kiosk query does not mutate identity or access
The kiosk SHALL present Player Info as a read-only flow and SHALL clear personal query state when returning home.

#### Scenario: View and leave Player Info
- **WHEN** a customer queries a member and returns to the kiosk home screen
- **THEN** no member, binding, balance, or play record is changed and the displayed personal data is cleared from the UI session

