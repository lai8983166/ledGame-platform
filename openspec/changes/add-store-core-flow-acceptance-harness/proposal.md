## Why

The core member, wristband, game, queue, and room flows now exist across three applications, but confidence still depends on manually starting each process and repeating the flow by hand. Because the system will be deployed without the developer on site, it needs a deterministic, one-command acceptance harness that proves the real clients and services still work together without touching operator data or physical hardware.

## What Changes

- Add a store acceptance harness that orchestrates the member platform, Member Admin, Registration Kiosk, game backend, and Electron game client in an isolated test environment.
- Add a disposable SQLite test database, deterministic fixture identities, configurable service endpoints, readiness checks, and simulated game/hardware controls.
- Add one browser/Electron golden-path acceptance scenario covering charge, member lookup or registration, wristband binding, game admission, simulated gameplay, queue promotion, room projection, settlement, and Player Info verification.
- Add focused recovery scenarios for duplicate operations, insufficient balance, platform disconnect/reconnect, and queue idempotency.
- Capture screenshots, service logs, step context, and relevant final state when an acceptance scenario fails.
- Extend the existing core-flow verification contract from API-only smoke coverage to real cross-client acceptance coverage while retaining the fast API suite.

## Capabilities

### New Capabilities

- `store-acceptance-harness`: Defines isolated process orchestration, deterministic fixtures, cross-client UI driving, simulated hardware/gameplay, and failure diagnostics for a local store environment.

### Modified Capabilities

- `core-flow-verification`: Adds one-command real-client acceptance verification above the existing SQLite/API core-flow tests.

## Impact

- `F:/project/ledGame-platform`: acceptance runner, Playwright browser tests, temporary SQLite configuration, stable UI selectors, service endpoint injection, and the top-level verification command.
- `F:/project/ledGame`: Electron acceptance entry, deterministic wristband keyboard input, debug-mode game completion, queue assertions, endpoint injection, and diagnostic capture.
- `F:/project/ledGame-backend`: isolated runtime configuration, simulated gameplay support where required, readiness signaling, and focused integration hooks that use production contracts.
- Development dependencies may add Playwright and Electron automation support; production member, wristband, and game data contracts remain unchanged.
