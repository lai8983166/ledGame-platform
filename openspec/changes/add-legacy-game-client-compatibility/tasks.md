## 1. Legacy HTTP entry points

- [x] 1.1 Add the second Spring Boot HTTP connector with legacy port `16668` as its default and a test override; verify the legacy listener and configured main API port answer independently.
- [x] 1.2 Implement `/dev/ping` and `/dev/gameCallback` JSON envelope parsing for `cmd=1/2/3/5`; verify exact legacy request fixtures, `code/msg/data` serialization, unsupported-command handling, and token array placement with protocol tests.
- [x] 1.3 Add sanitized diagnostics for rejected or unmatched legacy messages; verify logs identify command/room/request correlation without writing raw wristband UIDs or phone/member data.

## 2. Admission and session persistence

- [x] 2.1 Add an atomic batch wristband admission operation that validates all UIDs before activating any `READY` binding; verify one invalid or duplicate UID leaves every binding unchanged and `isAdmin` alone never grants access.
- [x] 2.2 Add an idempotent SQLite migration for legacy room/session linkage and `LEGACY_SETTLED` record support; verify an existing encrypted database retains all prior rows, indexes, triggers, and readable protected fields after migration.
- [x] 2.3 Implement `cmd=1` room presence keyed by normalized socket source IP with a 60-second expiry; verify multiple IPs remain independent, room names still merge from settings, and offline expiry does not delete business history.
- [x] 2.4 Implement `cmd=2` session association and one running record per eligible participant using a server-generated session ID; verify matching duplicate starts reuse the session and conflicting/unknown participants create no synthetic member or points.
- [x] 2.5 Persist open-session state and participant linkage in SQLite; verify a matching `cmd=3` after backend restart can still find the original session while the room remains offline until its next heartbeat.

## 3. Legacy final-score settlement and read models

- [x] 3.1 Implement legacy `cmd=3` settlement with exact non-negative 64-bit reported points, `LEGACY_SETTLED`, unknown success/termination reason, and a persisted callback fingerprint; verify duplicate settlement cannot increase points twice.
- [x] 3.2 Reject missing, extra, duplicate, negative, or session-mismatched score entries without assigning points to another member; verify the response and diagnostic identify the failure without exposing UID values.
- [x] 3.3 Update member totals, Player Info rank, admin leaderboard, and data export aggregations to include `COMPLETED` and `LEGACY_SETTLED` only; verify legacy values appear exactly once while `RUNNING` and `ABORTED` records remain excluded. (运营总览当前没有积分汇总指标，因此不新增一项。)
- [x] 3.4 Update room/member play projections to show active legacy game state and settled legacy results with an explicit unknown-end-reason marker; verify accepted settlement clears only the room's active-game state.

## 4. Compatibility acceptance

- [x] 4.1 Add protocol contract tests using the old client's exact `cmd=1/5/2/3` and ping payload shapes; verify admission errors use a non-200 business code and an old-compatible valid token carries correct UID, duration, and server-derived expiry.
- [x] 4.2 Add temporary-SQLite integration coverage for register/bind/admit, multiplayer start, exact point settlement, Player Info/leaderboard visibility, duplicate callbacks, invalid participants, and database restart recovery; verify no test data reaches a developer's persistent database.
- [x] 4.3 Add a packaged-backend smoke test or documented launch check for both ports `8090` and `16668`; verify old ping succeeds on `16668`, the newer health/API path still succeeds on `8090`, and a port conflict reports the occupied legacy port clearly.
- [x] 4.4 Document deployment/network requirements and protocol limits: open `16668` on the member-admin host, one distinguishable source IP per legacy room, no hardware-health inference, no retry guarantee, and `isAdmin` as non-authorizing metadata.
