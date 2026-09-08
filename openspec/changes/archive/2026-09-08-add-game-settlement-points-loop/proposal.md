## Why

The store core flow can charge, bind, admit, start, queue, and switch players, but the current acceptance path ends games through an operator abort and therefore does not prove that a natural game result becomes durable member points and ranking data. Result delivery is also best-effort in the game backend, so a temporary platform outage can permanently lose a completed game's settlement.

## What Changes

- Complete the normal game lifecycle from a natural simulation result through platform settlement, member points, ranking, Member Admin records, and Registration Kiosk Player Info.
- Make the platform authoritative for awarded points using an explicit initial scoring policy instead of trusting client-supplied points.
- Add a durable game-backend settlement outbox with immediate delivery, bounded retry/backoff, startup recovery, and observable pending/failed state.
- Preserve idempotency across repeated delivery, game-backend restart, and ambiguous HTTP outcomes so one game contributes points at most once.
- Order terminal handling so the result is durably captured before queue promotion; platform availability does not cause a completed result to disappear.
- Add test-first platform, game-backend, UI contract, and real cross-client acceptance coverage for normal completion, ranking, retry, restart recovery, and duplicate delivery.
- Keep manual stop/runtime failure records non-awarding and distinguish them from naturally completed games.

## Capabilities

### New Capabilities

- `game-settlement-delivery`: Durable capture, retry, recovery, observability, and queue-ordering guarantees for sending terminal game results from a game machine to the member platform.

### Modified Capabilities

- `game-play-records`: Make result settlement platform-authoritative for awarded points and define stable natural-completion versus abort behavior under duplicate delivery.
- `player-info-query`: Require naturally settled scores, awarded points, totals, and deterministic shared ranking to appear consistently in Player Info and Member Admin projections.
- `core-flow-verification`: Extend the real-client golden path and recovery suite through natural game completion, durable delivery, points, ranking, and duplicate-safe retry.

## Impact

- `F:/project/ledGame-platform`: settlement contract and scoring policy, SQLite play records and ranking queries, Member Admin record/member projections, Registration Kiosk Player Info, and Playwright acceptance scenarios.
- `F:/project/ledGame-backend`: terminal-result extraction, durable local settlement outbox, retry/recovery worker, delivery status diagnostics, and queue-promotion ordering.
- `F:/project/ledGame`: Debug Panel controls and stable visible state needed to drive a natural success/failure rather than an operator abort during acceptance.
- Existing play-start and settlement endpoints remain conceptually compatible, but client-supplied `pointsAwarded` ceases to be authoritative and should be removed or ignored after coordinated rollout.
