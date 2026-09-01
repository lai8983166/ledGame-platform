## MODIFIED Requirements

### Requirement: Time behavior is deterministic in tests
The platform SHALL obtain settlement and migration business time from an injectable clock, while tests SHALL submit explicit accumulated `RUNNING` duration rather than relying on real sleeping.

#### Scenario: Preparation time does not consume balance
- **WHEN** a test advances the configured wall clock after wristband validation but before any `RUNNING` settlement
- **THEN** the binding retains its complete remaining gameplay balance

#### Scenario: Settle explicit running duration
- **WHEN** a test settles a play with 75 accumulated `RUNNING` seconds
- **THEN** the binding balance decreases by exactly 75 seconds regardless of elapsed wall-clock time

#### Scenario: Advance a binding beyond expiry
- **WHEN** a test advances the configured wall clock beyond the timestamp that the legacy continuous-window model would have treated as expiry
- **THEN** the migrated binding keeps its persisted remaining gameplay balance because no `RUNNING` usage was settled

### Requirement: The core flow has one-command verification
The repository SHALL provide repeatable commands for fast core tests and the complete cross-service smoke flow, including `RUNNING`-only wristband consumption.

#### Scenario: Run fast verification
- **WHEN** a developer runs `pnpm test:core`
- **THEN** the relevant platform server, shared client, and kiosk tests run and the command fails if any critical contract or idempotent deduction rule fails

#### Scenario: Run end-to-end verification
- **WHEN** a developer runs `pnpm test:e2e`
- **THEN** the harness executes member creation, charge, bind, preparation validation, play start, natural and manual settlement, and Player Info verification with UID `2283055618` in isolated storage
- **AND** only simulated `RUNNING` intervals reduce the wristband balance

## ADDED Requirements

### Requirement: Recovery and multiplayer accounting are covered by verification
The complete verification SHALL cover retry-safe settlement, interrupted games, zero-runtime startup failure, and equal per-player deduction in multiplayer sessions.

#### Scenario: Retry an interrupted-game result
- **WHEN** the harness resubmits the same interrupted play result containing a persisted runtime checkpoint
- **THEN** the balance is reduced once by the checkpointed `RUNNING` duration and downtime is not charged

#### Scenario: Verify multiplayer deduction
- **WHEN** the harness completes a multiplayer game with two wristbands
- **THEN** both players are charged the same accumulated `RUNNING` duration exactly once
