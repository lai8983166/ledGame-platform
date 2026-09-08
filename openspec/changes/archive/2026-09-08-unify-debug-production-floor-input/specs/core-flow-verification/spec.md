## MODIFIED Requirements

### Requirement: The core flow has one-command verification
The repository SHALL provide repeatable commands for fast core tests, API-level end-to-end tests, and complete cross-client acceptance of both simulated interaction and production-shaped game input.

#### Scenario: Run fast verification
- **WHEN** a developer runs `pnpm test:core`
- **THEN** the relevant platform server, shared client, kiosk, normalized game-input, and adapter-contract tests run
- **AND** the command fails if any critical contract or Debug/ELC input equivalence check fails

#### Scenario: Run API end-to-end verification
- **WHEN** a developer runs `pnpm test:e2e`
- **THEN** the harness executes member creation, charge, bind, activate, play start, settlement, and Player Info verification with UID `2283055618` in isolated storage

#### Scenario: Run cross-client acceptance
- **WHEN** a developer runs `pnpm test:acceptance`
- **THEN** the harness starts the isolated store topology and completes the natural golden path through normalized `DOWN/UP` floor input rather than a Debug-only `click` rule path
- **AND** it verifies the resulting score, natural termination, settlement, queue promotion, points, ranking, Member Admin, and Player Info state

#### Scenario: Run production-shaped floor verification without physical hardware
- **WHEN** the acceptance suite starts a game with `runtimeMode=PRODUCTION` and the acceptance-only hardware readiness implementation
- **THEN** a test-owned bidirectional TCP floor receives at least one valid LED frame and returns production-format `DOWN/UP` input
- **AND** the game naturally completes through the same runtime and settlement path used by a real store installation

## ADDED Requirements

### Requirement: Automated production-shape coverage does not claim physical certification
Automated verification SHALL distinguish production-shaped software coverage from validation of a real controller, floor, electrical installation, and store network.

#### Scenario: Produce an automated acceptance report
- **WHEN** Debug and production-shaped acceptance scenarios pass without physical hardware
- **THEN** the Chinese report identifies the covered software boundaries
- **AND** it does not state that ELC-408 hardware, physical floor behavior, electrical wiring, color accuracy, or site networking has passed

#### Scenario: Prepare for a store release
- **WHEN** a release is ready for deployment to physical equipment
- **THEN** the operator documentation provides a short on-site smoke checklist for controller discovery, coordinate mapping, press/release input, LED output, and disconnect recovery

