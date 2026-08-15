## ADDED Requirements

### Requirement: Natural settlement has real-client acceptance coverage
The cross-client acceptance suite SHALL prove a naturally completed simulated game reaches durable platform records, points, ranking, Member Admin, and Registration Kiosk Player Info.

#### Scenario: Complete the natural golden path
- **WHEN** the acceptance harness uses Debug Panel simulation controls to satisfy the deterministic game objective
- **THEN** the game ends naturally, the platform play becomes `COMPLETED`, platform-derived points are positive, and both client projections agree

#### Scenario: Retry settlement after platform interruption
- **WHEN** the platform is unavailable after the game backend durably captures a natural result and later restarts
- **THEN** the pending delivery succeeds automatically and the play contributes points exactly once

#### Scenario: Restart game backend with pending settlement
- **WHEN** the game backend is restarted before a pending result can be delivered
- **THEN** the acceptance harness observes recovery from local storage and the same exactly-once points outcome

### Requirement: Settlement changes are developed test first
Changes to terminal result extraction, point calculation, delivery retry, ranking, or queue handoff SHALL begin with a failing focused test and SHALL pass the relevant repository suites and real-client acceptance scenarios before completion.

#### Scenario: Change settlement behavior
- **WHEN** implementation work changes a settlement or scoring rule
- **THEN** a test that demonstrates the missing behavior fails before the business code is changed and passes afterward

