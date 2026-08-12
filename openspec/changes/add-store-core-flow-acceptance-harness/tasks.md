## 1. Acceptance contract and test-first skeleton

- [x] 1.1 Add Playwright Test and a platform-owned acceptance project with serial execution, run-scoped artifacts, and a `pnpm test:acceptance` entry point.
- [x] 1.2 Write the named, step-based golden-path acceptance scenario before adding the missing testability seams and record its first expected failing boundary.
- [x] 1.3 Add focused unit tests for temporary-directory ownership, port allocation, bounded log capture, readiness timeout reporting, and scoped child-process cleanup.

## 2. Isolated configuration seams

- [x] 2.1 Make Member Admin and Registration Kiosk platform API endpoints injectable for acceptance runs while preserving their current validated defaults, with focused tests for invalid and overridden values.
- [x] 2.2 Add a platform-server acceptance profile that places SQLite and all mutable server state under a run-owned temporary directory and exposes an explicit readiness check.
- [x] 2.3 Make the game backend's platform endpoint, persistent data paths, simulation mode, and readiness check injectable without weakening normal runtime validation.
- [x] 2.4 Add an Electron acceptance launch configuration that targets the isolated game backend and platform endpoints without reading or rewriting operator settings.

## 3. Stable cross-client interaction surfaces

- [x] 3.1 Add stable business-level selectors to Member Admin charge, wristband status/action, room status, member, and play-record surfaces without coupling tests to translated copy or layout.
- [x] 3.2 Add stable selectors to Registration Kiosk phone lookup/registration, wristband scan/bind, success, and Player Info surfaces while preserving real keyboard scan handling.
- [x] 3.3 Add stable selectors to the Electron game entry, preparation, scanned-player state, queue workflow, Debug Panel lifecycle controls, and authoritative result state.
- [x] 3.4 Add focused UI/contract tests proving selectors expose authoritative state and simulated scans still traverse the production numeric-UID-plus-Enter reader path.

## 4. Store process orchestration

- [x] 4.1 Implement a Node acceptance fixture that creates one run directory, allocates loopback ports, and launches the platform server and both Vite clients with labeled bounded logs.
- [x] 4.2 Extend the fixture to launch the game backend and Playwright-controlled Electron client with isolated configuration and no visible helper terminals.
- [x] 4.3 Implement readiness waits, early-exit detection, reverse-order graceful shutdown, scoped Windows process-tree termination, and cleanup that cannot target unrelated processes or operator paths.
- [x] 4.4 Preserve traces, screenshots, step metadata, configuration summary, and bounded process-log tails on failure, with an opt-in flag to retain disposable runtime storage.

## 5. Golden-path cross-client acceptance

- [x] 5.1 Drive Member Admin through charging the declared primary and queued wristbands and assert the visible authoritative wristband state.
- [x] 5.2 Drive Registration Kiosk through member lookup or creation, numeric keyboard scan, binding, repeated-bind feedback, and Player Info verification.
- [x] 5.3 Drive Electron from IDLE into game configuration, scan the primary wristband, confirm admission/balance, select deterministic options, and start simulated gameplay.
- [x] 5.4 While gameplay is active, bind and enqueue the second eligible wristband through the real queue UI and assert the visible waiting state.
- [x] 5.5 End the current game through the Debug Panel, assert settlement and automatic queue promotion, and verify the next game becomes current.
- [x] 5.6 Assert Member Admin room projection and records plus Registration Kiosk Player Info agree with the final public API state, without reading internal client stores or database tables.

## 6. Focused rejection and recovery scenarios

- [x] 6.1 Add isolated acceptance coverage for insufficient balance and duplicate binding, asserting stable UI feedback and unchanged authoritative state.
- [x] 6.2 Add isolated acceptance coverage for duplicate enqueue/idempotency so one wristband produces at most one waiting entry.
- [x] 6.3 Add isolated acceptance coverage for platform interruption and restart, asserting automatic game reconnection, one IP-identified room, and preserved consistent queue state.

## 7. Verification and operating guidance

- [x] 7.1 Run the harness repeatedly after both success and forced failure to prove clean startup, bounded diagnostics, and no state leakage between runs.
- [x] 7.2 Run platform typechecks, client/server suites, API smoke verification, game frontend tests/build, and game backend Maven tests after the acceptance suite passes.
- [x] 7.3 Document prerequisites, commands, expected runtime, artifact locations, retained-storage diagnostics, and the separate on-site hardware checklist boundary.
- [x] 7.4 Strictly validate the OpenSpec change and record any deferred installer, LAN, physical hardware, or non-core UI coverage.
- [x] 7.5 Translate the acceptance operating guide, report scenario/step names, and failure attachment names into Chinese, then regenerate the report.
