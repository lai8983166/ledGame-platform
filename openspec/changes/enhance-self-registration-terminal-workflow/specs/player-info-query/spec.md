## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Player information exposes ranking and play history
The kiosk player-information result SHALL expose the member's current total points and rank together with a bounded list of recent play records, including game name, status, time, score, and awarded points when available.

#### Scenario: Member has settled and recent games
- **WHEN** a queried member has settled games and recent play records
- **THEN** the result shows the rank and total points and lists those recent games without fabricating missing values

#### Scenario: Member has no games
- **WHEN** a queried member has no play records
- **THEN** the result shows zero or the server-defined empty rank value and an explicit empty-history state
