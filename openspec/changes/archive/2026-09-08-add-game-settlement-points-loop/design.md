## Context

The platform already persists `RUNNING`, `COMPLETED`, and `ABORTED` play records and derives Player Info totals and rank from `points_awarded`. The game backend already starts platform play records and extracts a simple-game score at terminal callbacks. However, its result reporter retries only in memory, catches final delivery failure, and then continues queue handling, so a platform outage can permanently lose the result. The platform also accepts `pointsAwarded` from the game machine, which makes a client authoritative for member value.

This change spans the platform SQLite service, the game backend's persistent local database and runtime callbacks, two browser clients, the Electron simulation path, and the cross-repository acceptance harness. It must remain usable on a single Windows game machine without adding a broker or cloud dependency.

## Goals / Non-Goals

**Goals:**

- Turn a natural game termination into an immutable platform settlement, awarded points, total, rank, and consistent client projections.
- Prevent temporary platform outages, lost responses, or game-backend restart from losing or double-counting a result.
- Make the platform the sole authority for awarded points.
- Capture a terminal result durably before queue promotion or runtime cleanup can make it unreachable.
- Keep diagnostics and automated tests sufficient for remote support without physical hardware.

**Non-Goals:**

- Designing the final commercial points economy, rewards catalog, redemption, levels, seasons, or anti-cheat system.
- Synchronizing results between multiple stores or a cloud platform.
- Recovering an in-progress game after abrupt machine power loss before any terminal callback occurred.
- Guaranteeing physical controller, floor, audio, LAN, installer, or Windows service behavior.
- Converting every game type to a rich game-specific score model in this change.

## Decisions

1. **Use a local transactional outbox in the game backend.** A new settlement-delivery table stores one immutable payload per platform play ID, its state, attempts, retry time, and last error. Terminal callbacks write the outbox before queue handoff; an immediate attempt reduces normal latency and a scheduled worker resumes retryable items. Reusing the game backend's database is preferred over an in-memory retry loop, filesystem JSON, Redis, or a message broker because it is already local, transactional, testable with H2, and requires no new service.

2. **Provide at-least-once transport with idempotent platform application.** Network delivery cannot reliably distinguish “request never arrived” from “platform committed but response was lost.” Every retry therefore targets the same platform play ID with the same immutable payload. The platform's first terminal update wins; later retries return the stored record. This produces exactly-once points effects without pretending the network provides exactly-once delivery.

3. **Classify retryable and permanent failures.** Connection failures, timeouts, and platform 5xx responses remain `PENDING` and use capped exponential backoff with configurable acceptance-test intervals. Stable 4xx validation/business errors become `FAILED` and retain a sanitized code for diagnostics. Infinite tight retry and silently dropping poison messages are both rejected.

4. **Keep queue progress dependent on durable capture, not synchronous remote success.** A natural completion or abort may promote the queue once its settlement payload is safely committed locally. Requiring a live platform response would freeze the room during a short outage; promoting before local persistence could lose the result. If the next queued player needs platform admission while the platform is offline, existing fail-closed access behavior remains authoritative.

5. **Make scoring a versioned platform policy.** The settlement request carries raw outcome data but not authoritative points. A platform `GamePointsPolicy` calculates an award decision from the persisted game identity, terminal class, and raw score. The initial `raw-score-v1` rule awards `max(0, rawScore)` for natural completion and zero otherwise. Persisting `scoring_policy` beside `points_awarded` makes historical records auditable and allows later per-game policies without retroactively changing totals.

6. **Retain transitional request compatibility.** During coordinated rollout, the platform may deserialize the existing `pointsAwarded` request field but ignores it; the updated game backend stops sending it. This avoids deployment-order fragility while removing client authority. The response continues to expose the stored awarded points.

7. **Use natural simulation rather than operator End Game for the new golden path.** The acceptance harness drives the existing production debug input controls to hit the deterministic blue target and cause `NATURAL_SUCCESS`. The older abort path remains a focused negative scenario. Assertions use public APIs and visible Member Admin/Player Info state, never direct database reads.

8. **Expose a narrow local delivery diagnostic projection.** The game backend reports counts plus settlement ID, platform play ID, state, attempts, next attempt, and stable error code. It omits phone, member profile, and full result payload by default. This supports tests and remote operations without turning the outbox into another admin system.

## Risks / Trade-offs

- [The initial one-point-per-raw-score rule may not fit every future game] → Isolate it behind a versioned policy and persist the version; add game-specific policies in later changes without rewriting history.
- [A local database outage can still prevent durable terminal capture] → Fail the terminal handoff visibly, retain runtime diagnostics, and do not claim queue promotion succeeded until persistence succeeds.
- [Permanent rejected deliveries require operator action] → Expose failed counts and codes; defer a full retry/edit administration UI until real operational demand is known.
- [Natural completion timing can race with queue callbacks] → Put durable capture and callback ordering under focused concurrency tests and make local insertion idempotent by platform play ID.
- [Result payloads can grow or contain sensitive data] → Store a bounded, game-defined diagnostic snapshot and keep the public diagnostic endpoint metadata-only.
- [Two active changes touch core-flow verification] → Add new requirements instead of rewriting the existing one-command requirement, and archive completed prerequisite changes before this change is finalized.

## Migration Plan

1. Add the platform scoring-policy column with a safe nullable/default migration and make settlement calculate authoritative points while accepting legacy request JSON.
2. Deploy the platform before or together with the updated game backend.
3. Add the game-backend outbox schema, repository, delivery worker, and diagnostic projection; existing installations create the table through the normal schema migration path.
4. Route terminal callbacks through durable capture, then remove in-memory-only result retry as the source of truth.
5. Update both client projections and acceptance tests, then run all three repository suites and the cross-client suite.
6. Rollback may leave unused outbox rows and scoring-policy columns; older code ignores them. Do not delete pending rows during rollback.

## Open Questions

- During implementation, inventory each supported runtime's score snapshot. This change guarantees the simple-game path; unsupported game types must award zero with an explicit diagnostic payload until a later policy/extractor is added.

## Deferred Follow-ups

- Add versioned, game-specific scoring policies after each supported runtime exposes a reviewed score snapshot.
- Design rewards, redemption, levels, seasons, and the commercial points economy separately from settlement transport.
- Add store-to-cloud synchronization only after the single-store local workflow is operationally stable.
- Treat abrupt power loss before any terminal callback as a later runtime-recovery problem; this change only guarantees delivery after a terminal result has been durably captured.

## Persistence Discovery

The game backend currently uses one H2 schema in both production-shaped and acceptance runs. Production uses the file database `jdbc:h2:file:./database/runtime/ledgame` with MySQL compatibility, while acceptance injects an isolated in-memory H2 URL with the same compatibility mode. `spring.sql.init.mode=always` applies the idempotent `classpath:schema.sql` at every startup; there is no Flyway or Liquibase dependency. The outbox therefore belongs in `schema.sql` using `CREATE TABLE IF NOT EXISTS` plus narrowly compatible `ALTER` statements only when needed. Restart recovery is exercised against the file-backed H2 profile, while fast persistence tests may use isolated in-memory H2 databases.
