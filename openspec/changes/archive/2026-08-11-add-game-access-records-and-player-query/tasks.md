## 1. Existing Core-Flow Regression Baseline

- [x] 1.1 Add a platform-server test profile that creates a unique temporary SQLite database and deletes it after the suite.
- [x] 1.2 Write failing API integration tests for member lookup/create and active-phone uniqueness.
- [x] 1.3 Write failing API integration tests for charge UID `2283055618`, duplicate/unavailable charge, and balance clearing.
- [x] 1.4 Write failing API integration tests for charged-to-ready binding, exact duplicate-bind error, and ready unbind.
- [x] 1.5 Refactor only as needed to make the existing core-flow regression tests pass against real SQLite.

## 2. Deterministic Access-Time Tests and Model

- [x] 2.1 Write failing tests proving first valid admission activates `READY`, stores server time, and returns expiry and remaining seconds.
- [x] 2.2 Write failing tests proving repeated `ACTIVE` admission does not reset or extend the purchased window.
- [x] 2.3 Write failing tests for unknown, charged-but-unbound, frozen/ineligible, and expired admission error codes.
- [x] 2.4 Introduce an injectable application `Clock` and implement transactional activation and expiry until the tests pass.
- [x] 2.5 Extend wristband and member query mapping to return server-derived balance fields without accepting client balance values.

## 3. Per-Game Record Tests and Persistence

- [x] 3.1 Write failing schema and API tests for creating one running play linked to member, binding, UID, device, room, and selected game.
- [x] 3.2 Write failing tests for `(deviceId, externalSessionId)` start idempotency and concurrent-use rejection.
- [x] 3.3 Write failing tests for natural completion, failure, manual stop, startup abort, and duplicate result settlement.
- [x] 3.4 Add the idempotent `game_play_records` table and indexes using SQLite-compatible initialization.
- [x] 3.5 Implement play start and result services/controllers until all record and idempotency tests pass.
- [x] 3.6 Write and pass a regression test proving one game settlement leaves unexpired purchased access active for another game.

## 4. Player Info Query Tests and Kiosk

- [x] 4.1 Write failing API tests for existing/unknown phone, profile data, points total, ranking, wristband balance, and recent plays.
- [x] 4.2 Implement the aggregate Player Info query from persisted members, bindings, wristbands, and settled plays.
- [x] 4.3 Add shared API-client types and tests for Player Info success and stable failure responses.
- [x] 4.4 Write failing kiosk flow tests for entering a phone, displaying returned information, handling not-found/service errors, and clearing personal state on home.
- [x] 4.5 Replace the kiosk Player Info placeholder with the tested read-only query and result screens.

## 5. Automated Core Verification

- [x] 5.1 Add `test:core` orchestration for platform-server, shared-client, and kiosk critical tests.
- [x] 5.2 Write a failing isolated smoke scenario for member creation, charge, bind, activate, play start, settlement, and Player Info using UID `2283055618`.
- [x] 5.3 Implement the `test:e2e` harness and temporary-service lifecycle until the smoke scenario passes without physical hardware.
- [x] 5.4 Run focused tests after each implementation group and run both `test:core` and `test:e2e` as final verification.
- [x] 5.5 Document the test-first rule, commands, isolated database behavior, and the remaining one-time physical reader acceptance check.
