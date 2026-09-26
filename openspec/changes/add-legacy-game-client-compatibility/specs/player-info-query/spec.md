## MODIFIED Requirements

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
