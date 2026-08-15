## MODIFIED Requirements

### Requirement: Player points and rank are derived from settled games
The platform SHALL calculate total points only from naturally completed play records using their persisted platform-awarded points, and SHALL assign active members a deterministic shared rank from the same source.

#### Scenario: Two settled games contribute to points
- **WHEN** a member has two naturally completed plays with platform-awarded points
- **THEN** Player Info returns the sum once for each play and a rank consistent with all active members' totals

#### Scenario: Members have equal totals
- **WHEN** two active members have the same total awarded points
- **THEN** both receive the same rank and the next lower total is ranked after the number of strictly higher totals

#### Scenario: Running or aborted play has no awarded points
- **WHEN** a play is running, manually stopped, startup-aborted, or failed before natural completion
- **THEN** it does not increase the member's total or ranking value

## ADDED Requirements

### Requirement: Operator and player settlement projections agree
Member Admin and Registration Kiosk Player Info SHALL present the same persisted score, awarded points, terminal status, points total, and rank returned by public platform APIs.

#### Scenario: View a naturally completed game
- **WHEN** settlement delivery succeeds and both clients refresh their data
- **THEN** Member Admin shows the completed play with raw score and awarded points while Player Info shows the same play and updated total and rank

#### Scenario: View an aborted game
- **WHEN** a member game was manually stopped or aborted
- **THEN** both clients identify it as non-completed and show zero awarded points

