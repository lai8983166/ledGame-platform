## 1. Test-first contracts and persistence discovery

- [x] 1.1 Inspect and document the game backend's production schema/migration mechanism, database dialects, and restart lifecycle before choosing the outbox migration format.
- [x] 1.2 Add failing platform integration tests for `raw-score-v1`, ignored client-awarded points, null/negative scores, natural failure, abort-zero-points, first-settlement-wins, and duplicate-safe totals.
- [x] 1.3 Add failing platform integration tests for shared ranking ties and consistent play/points projections across play-list, member, and Player Info APIs.
- [x] 1.4 Add failing game-backend persistence tests for one immutable outbox row per platform play ID, pending-row reload after application restart, and safe concurrent duplicate capture.
- [x] 1.5 Add failing game-backend delivery tests for immediate success, lost-response retry, retryable outage, permanent rejection, capped backoff, and startup recovery.
- [x] 1.6 Add failing runtime callback/queue tests proving terminal settlement is durably captured before queue promotion for natural completion, manual stop, startup abort, and runtime failure.
- [x] 1.7 Add a failing real-client acceptance scenario that naturally completes the deterministic game and expects positive platform-derived points in both Member Admin and Player Info.

## 2. Platform-authoritative settlement and ranking

- [x] 2.1 Add a backward-compatible SQLite schema migration for persisted scoring-policy version and update isolated test schema assertions.
- [x] 2.2 Implement a `GamePointsPolicy` boundary and the initial `raw-score-v1` policy that awards `max(0, rawScore)` only for natural terminal results.
- [x] 2.3 Change settlement handling to ignore legacy client `pointsAwarded`, persist the policy decision atomically with the terminal play, and return the first committed result for all repeats or conflicts.
- [x] 2.4 Preserve stable `COMPLETED` versus `ABORTED` classification and zero points for manual stop, startup abort, and runtime failure.
- [x] 2.5 Include raw score, awarded points, scoring-policy version, and terminal reason in public play-record projections without exposing stored implementation JSON.
- [x] 2.6 Make member totals and deterministic shared rank derive only from persisted platform-awarded points on naturally completed plays.
- [x] 2.7 Run the focused real-SQLite settlement, points, ranking, and Player Info integration tests and record the passing boundary.

## 3. Durable game-backend settlement delivery

- [x] 3.1 Add the native production and H2 acceptance migrations for a settlement outbox with unique platform play ID, immutable payload, state, attempts, retry time, timestamps, and stable error fields.
- [x] 3.2 Implement transactional/idempotent outbox capture and metadata-only query projections for `PENDING`, `DELIVERED`, and permanently `FAILED` deliveries.
- [x] 3.3 Update the member-platform settlement contract to send raw terminal data without authoritative points while remaining compatible with the platform transition contract.
- [x] 3.4 Implement immediate delivery and a configurable scheduled worker that claims due rows safely and prevents concurrent double delivery.
- [x] 3.5 Classify connection errors, timeouts, and 5xx responses as retryable with capped exponential backoff, and classify stable 4xx responses as permanent failures.
- [x] 3.6 Resume pending delivery automatically on game-backend startup and mark a lost-response retry delivered from the platform's idempotent stored response.
- [x] 3.7 Expose local settlement-delivery counts and sanitized pending/failed diagnostics through a stable endpoint used by tests and remote support.
- [x] 3.8 Replace the in-memory-only terminal reporter with durable capture for natural completion, manual stop, startup abort, and runtime failure.
- [x] 3.9 Enforce and test callback ordering so queue promotion occurs only after durable capture succeeds, without requiring synchronous platform delivery success.

## 4. Visible score, points, and ranking projections

- [x] 4.1 Extend Member Admin's real play-record surface to distinguish raw score from awarded member points and display terminal status/reason from the public API.
- [x] 4.2 Extend Member Admin's real member projection to show each active member's platform-derived points total and shared rank.
- [x] 4.3 Verify Registration Kiosk Player Info refreshes and displays the settled play, awarded points, updated total, and shared rank using stable business selectors.
- [x] 4.4 Add or update focused client contract tests so both UIs consume authoritative fields and never calculate totals or ranking locally.
- [x] 4.5 Add a deterministic Debug Panel action and stable selectors that complete the acceptance game through the production natural-success path rather than operator `End Game`.

## 5. Cross-client delivery and recovery acceptance

- [x] 5.1 Change the golden path to complete naturally, wait for delivery, and assert one `COMPLETED` play with positive `raw-score-v1` points before queue promotion/current-player verification.
- [x] 5.2 Assert Member Admin records/member rank and Registration Kiosk Player Info show the same raw score, awarded points, total, and rank after natural completion.
- [x] 5.3 Add an isolated outage scenario that stops the platform after durable result capture, verifies one pending delivery, restarts the platform, and observes automatic exactly-once settlement.
- [x] 5.4 Add an isolated recovery scenario that restarts the game backend with a pending delivery and observes delivery from local storage without recreating the finished game session.
- [x] 5.5 Add duplicate-delivery and conflicting-payload scenarios that prove one terminal record and one points contribution.
- [x] 5.6 Retain the manual-abort scenario as a negative control and assert `ABORTED`, zero awarded points, and no rank increase.

## 6. Full verification and operating guidance

- [x] 6.1 Run platform typechecks/build, server tests, real-SQLite API smoke, and focused client suites.
- [x] 6.2 Run all game frontend tests/build and the complete game-backend Maven suite against both production-shaped and H2 acceptance configuration.
- [x] 6.3 Run the serial cross-client acceptance suite repeatedly across success, forced delivery failure, platform restart, and game-backend restart, confirming cleanup and no state leakage.
- [x] 6.4 Update the Chinese acceptance guide/report descriptions with settlement delivery states, expected runtime, retry diagnostics, and the remaining physical/on-site boundary.
- [x] 6.5 Strictly validate the OpenSpec change and record deferred per-game scoring policies, rewards/redemption, cloud synchronization, and abrupt in-progress power-loss recovery.
