# core-flow-verification Specification

## Purpose
TBD - created by archiving change add-game-access-records-and-player-query. Update Purpose after archive.
## Requirements
### Requirement: Platform business rules have real-SQLite integration tests
The project SHALL verify member, wristband, timer, play-record, points, ranking, and player-query behavior against an isolated SQLite database.

#### Scenario: Run the platform test suite
- **WHEN** a developer runs the platform server tests
- **THEN** each test uses isolated data, exercises the real SQLite driver, and leaves the operator database unchanged

### Requirement: Time behavior is deterministic in tests
The platform SHALL obtain business time from an injectable clock so expiry scenarios do not rely on real sleeping.

#### Scenario: Advance a binding beyond expiry
- **WHEN** a test advances the configured clock past the calculated expiry
- **THEN** the next access decision deterministically rejects and expires the binding without waiting in wall-clock time

### Requirement: The core flow has one-command verification
The repository SHALL provide repeatable commands for fast core tests and the complete cross-service smoke flow.

#### Scenario: Run fast verification
- **WHEN** a developer runs `pnpm test:core`
- **THEN** the relevant platform server, shared client, and kiosk tests run and the command fails if any critical contract fails

#### Scenario: Run end-to-end verification
- **WHEN** a developer runs `pnpm test:e2e`
- **THEN** the harness executes member creation, charge, bind, activate, play start, settlement, and Player Info verification with UID `2283055618` in isolated storage

### Requirement: Core behavior is developed test first
Every change to the critical member-wristband-game flow SHALL begin with a failing automated scenario and SHALL pass focused and full core tests before completion.

#### Scenario: Add or fix a core rule
- **WHEN** a developer changes a core business rule or fixes a regression
- **THEN** a test demonstrating the missing or broken behavior fails before the implementation and passes after it

