## Why

The member platform currently stops at `READY`: it can charge and bind a wristband, but it cannot authorize a game-system swipe, start the purchased timer, persist per-game results, or answer the kiosk's Player Info Query from SQLite. These behaviors form the next core business loop and need automated coverage before implementation so future changes do not require repeatedly exercising the entire flow by hand.

## What Changes

- Add a game-access API that validates a bound wristband, activates its purchased time on the first valid game-system swipe, and returns authoritative member and remaining-time data.
- Add idempotent per-game start and result APIs backed by SQLite, keeping the purchased wristband time window separate from individual game records.
- Add a player-information query that returns registration data, points, rank, current wristbands, balances, and recent games by phone number.
- Add real-SQLite integration tests for the existing charge/bind flow and the new activation, expiry, game-record, points, ranking, and query behavior before implementing each behavior.
- Add repeatable core and end-to-end test entry points so the main flow can be verified without a physical reader or manual UI operation.
- Connect the registration kiosk's existing Player Info Query entry to the new persisted query instead of its placeholder action.

## Capabilities

### New Capabilities

- `game-access-entitlement`: Authoritative game-system wristband activation, remaining-time calculation, expiry, and admission errors.
- `game-play-records`: Idempotent creation and settlement of individual game records without ending the enclosing purchased time window.
- `player-info-query`: Aggregated member profile, points, ranking, wristband balance, and recent-play lookup by phone.
- `core-flow-verification`: Automated real-SQLite and cross-service verification entry points for the critical member and wristband flow.

### Modified Capabilities

None. The repository has no synchronized main specs yet; the existing charge-and-bind behavior is captured as regression coverage in this change.

## Impact

- Affects the Spring Boot platform server, SQLite schema, registration-kiosk UI, shared API client, and root test commands.
- Adds internal HTTP contracts consumed by `ledGame-backend`; the game frontend remains isolated from the platform database and platform API.
- Requires stable error codes, server-authoritative time, idempotency keys, and temporary SQLite databases in tests.
- Does not require a second database or direct database sharing with either game project.
