## Context

The platform server is the sole owner of member, wristband, and binding data in a local SQLite database. The implemented flow currently ends after a charged wristband is bound to a member in `READY`; the schema already contains `ACTIVE`, `EXPIRED`, `started_at`, and `ended_at`, but no endpoint drives those states and no table stores individual games. The registration kiosk also exposes a Player Info Query entry that is still a placeholder.

The game renderer and Electron process already read the physical numeric UID, while a separate Spring Boot game backend owns game preparation and runtime. The platform must therefore expose a server-to-server contract rather than allow either game frontend or game backend to open the SQLite file.

## Goals / Non-Goals

**Goals:**

- Make the first valid game-system swipe the authoritative `READY` to `ACTIVE` transition.
- Return server-derived expiry and remaining time for every admission decision.
- Store each game separately from the purchased wristband time window and settle it idempotently.
- Derive member points, ranking, current wristbands, balances, and recent play from persisted data.
- Establish tests-first development with real temporary SQLite databases and a repeatable full-flow smoke test.

**Non-Goals:**

- Sharing the SQLite file with another process or moving member data into the game backend's H2 database.
- Adding online/cloud synchronization, multi-store identity, or remote operations tooling.
- Defining complex per-game point conversion rules; the first version stores both raw score and awarded points.
- Automating physical reader, LED controller, or touchscreen hardware acceptance.
- Enforcing a hard mid-game stop at the exact expiry instant in this MVP.

## Decisions

### The platform server owns access time

`POST /api/game-access/activate` accepts the reader UID plus device metadata. In one SQLite transaction it finds the open binding, rejects ineligible states, changes `READY` to `ACTIVE`, and writes `started_at` using a server-injected `Clock`. An already `ACTIVE` binding returns its existing window without resetting the start time. Expiry is calculated from `started_at + duration_minutes`; when the calculated expiry is not after now, the binding and wristband are changed to `EXPIRED` before returning a stable denial.

This keeps Windows clients and multiple game machines from deciding time independently. Extending the existing binding fields is preferred over adding a second entitlement table or duplicating an `expires_at` column.

### Purchased access and individual games are separate records

The existing `wristband_bindings` row represents the whole purchased time window. A new `game_play_records` table represents one selected game and contains member, binding, UID snapshot, external preparation ID, device/room, game identity, timestamps, outcome, raw score, awarded points, and optional result JSON.

Finishing a game updates only its play record. It does not end an `ACTIVE` binding. A new game can start while the window remains valid; after expiry no new play may start. The MVP permits a game that was admitted before expiry to finish naturally.

### Game APIs are idempotent and fail closed

`POST /api/game-plays/start` revalidates the binding and remaining time, creates one running play, and uses `(device_id, external_session_id)` as a unique idempotency key. `PUT /api/game-plays/{playId}/result` is idempotent: a repeated equivalent result returns the existing settlement and never awards points twice.

Unknown, unbound, expired, frozen, already-in-use, and service validation failures expose stable codes with user-readable messages. SQLite constraints remain the final guard against duplicate open plays under concurrent requests.

### Player information is an aggregate read model

`GET /api/player-info?phone=...` returns the active member profile, total awarded points, rank, current wristband list with remaining time, and recent play records. Points and rank are derived from settled play records for the small-store MVP instead of maintaining a second mutable total on `members`.

The kiosk calls this endpoint through the shared API client. It does not query multiple tables from the browser and it clears returned personal data when returning home.

### Tests precede implementation

Each task that changes business behavior begins with a failing test. Platform API integration tests use `@SpringBootTest` and a unique temporary SQLite file; H2 is not substituted because SQLite transaction and constraint behavior is part of the contract. Time-dependent tests replace the application `Clock`, so expiry is tested by advancing time rather than sleeping.

A root `test:core` command runs fast platform, kiosk, and contract tests. A separate `test:e2e` harness starts isolated services or uses their test fixtures and executes charge, bind, activate, play, settle, and player-query with UID `2283055618`. Test data never enters the operator's normal database.

### The platform API remains server-to-server for game admission

The game renderer and Electron layer never call these endpoints directly. `ledGame-backend` is the caller and receives its platform base URL from configuration. If it runs on another Windows machine, the configured URL uses the member-management machine's stable LAN address. Browser CORS settings are not used as service authentication.

## Risks / Trade-offs

- [A process can stop after activation but before a game starts, so purchased time continues] → This matches the declared rule that the first valid game-system swipe starts timing; the UI must clearly show the active countdown.
- [A game may finish after the access window expires] → The MVP rechecks at every game start and records the finish; exact hard-stop behavior can be added later with an explicit engine termination reason.
- [A crashed game backend can leave a play marked running] → Expired access denies new admission automatically, and the admin/manual recovery path can close a stale record; leases and heartbeats remain out of scope.
- [Derived rank queries become slower as history grows] → The expected single-store volume is small; indexes are added now and an aggregate table can be introduced only when measurements justify it.
- [Two services can drift on JSON fields or error codes] → Both repositories receive contract tests using the same documented examples and idempotency rules.

## Migration Plan

1. Add failing regression tests for the existing charge, member, bind, clear, and unbind flow.
2. Add the new play-record table using idempotent SQLite schema initialization and introduce the injectable server clock.
3. Implement activation and play APIs behind tests, then implement the player aggregate query.
4. Connect the kiosk query UI only after the server contract passes.
5. Add fast and end-to-end commands and run them against an isolated database before enabling the game backend integration.

Rollback removes the new callers first. The added play table can remain unused without changing existing charge and bind behavior; existing member and binding rows remain compatible.

## Open Questions

- The initial game backend may submit `pointsAwarded = max(rawScore, 0)`; a later product decision can introduce game-specific conversion without changing historical raw scores.
- A later change must decide whether expiry forcibly stops a running game or permits the current game to finish, if the MVP policy proves insufficient.
