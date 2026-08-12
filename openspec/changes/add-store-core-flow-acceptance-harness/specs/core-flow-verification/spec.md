## MODIFIED Requirements

### Requirement: The core flow has one-command verification
The repository SHALL provide repeatable commands for fast core tests, the isolated API smoke flow, and the real-client store acceptance flow.

#### Scenario: Run fast verification
- **WHEN** a developer runs `pnpm test:core`
- **THEN** the relevant platform server, shared client, and kiosk tests run and the command fails if any critical contract fails

#### Scenario: Run end-to-end API verification
- **WHEN** a developer runs `pnpm test:e2e`
- **THEN** the harness executes member creation, charge, bind, activate, play start, settlement, and Player Info verification with UID `2283055618` in isolated storage

#### Scenario: Run real-client store acceptance verification
- **WHEN** a developer runs `pnpm test:acceptance`
- **THEN** the harness starts the isolated multi-process store environment and drives the golden path through Member Admin, Registration Kiosk, and the Electron game client
- **AND** the command exits successfully only after the cross-client state is verified and every harness-owned process is cleaned up

