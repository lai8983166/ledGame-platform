## Context

See `proposal.md` for motivation and `specs/` for the observable contract. The current platform runs one Spring Boot application on its configured main port (default 8090), stores members, wristband access and per-member play records in SQLite, and derives points/rankings from settled play records. It has no fixed `/dev/gameCallback` or `/dev/ping` routes. Legacy game clients hard-code port 16668 and send heartbeat, admission, start and end messages to those routes; start/end payloads have no session ID, event sequence, termination reason or retry protocol.

## Goals / Non-Goals

**Goals:**

- Accept the four fixed legacy callback commands and ping request without changing the new game's routes or contract.
- Reuse current wristband/member eligibility and persistence rules; create ordinary per-member records for real legacy participants.
- Preserve old reported final points exactly, make them visible through the same member total and ranking source, and avoid claiming that an unknown legacy stop was a natural completion.
- Project a room by the legacy terminal's source IP, exposing only heartbeat and game state the old messages actually provide.
- Make accepted sessions survive a platform restart and make normal duplicate starts/end callbacks safe against double records or double awards.

**Non-Goals:**

- Implement `/console/forward` as an arbitrary proxy, infer its unknown target paths, or implement the separate optional `notifyURL` callback.
- Add a device-registration system, tokens, client changes, real-time scores, queue state, controller health checks, or hardware telemetry to the old protocol.
- Treat a legacy `cmd=3` as proof of natural success; the old request carries no stop reason.

## Decisions

1. **Add a second HTTP connector to the existing application.** The old client cannot change its `16668` port, while the newer clients keep using the configured main port (default `8090`). Add an additional embedded Tomcat connector to the same Spring application context, defaulting to `16668`; allow a property override for tests and exceptional deployments. This reuses the same database, license/startup gates, and service lifecycle. Do not create a second Spring process or relay requests over loopback. Verify both ports at integration level and report a clear startup error if the legacy port is unavailable.

2. **Keep protocol translation in a narrow adapter and call domain services directly.** A legacy controller validates the JSON envelope, passes normalized values to a compatibility service, and serializes the legacy `code/msg/data` envelope. The adapter calls `GameAccessService`, `GamePlayService`, and existing room/leaderboard projections inside the application; it does not call the platform's own HTTP endpoints. For `cmd=5`, add a transactional batch operation that validates all distinct UIDs and their members/access before activating any `READY` binding. Return an ordered legacy `data` list with the token at index 1; use server-derived expiry and duration. Match the old client's one-minute minimum remaining-time condition instead of returning a token the old client will reject.

3. **Use one persisted compatibility session per legacy room IP.** Obtain identity from the socket's remote address, normalize loopback and IPv4-mapped IPv6 consistently, and ignore forwarded-IP headers. Use the IP as both legacy device and room identity. Persist an internal UUID for each accepted `cmd=2` and pass it as `external_session_id` when creating one `RUNNING` game record per eligible wristband. Keep a small legacy-session row linking room IP, game, ordered participants, current status and end-callback fingerprint. This lets `cmd=3` resume after a server restart. A repeated `cmd=2` matching the currently running room/game/ordered participants returns that existing session; a conflicting start is rejected rather than overwriting an open session.

4. **Represent old points as a separate settled result.** The modern settlement path recomputes awards from the platform's current policy and correctly maps non-natural results to `ABORTED`; it cannot represent an old final award without falsely applying new rules. Add a legacy-specific settlement operation that stores the reported non-negative signed-64-bit `points` directly as `points_awarded`, records scoring policy `legacy-reported-v1`, sets success to unknown, and uses status `LEGACY_SETTLED` plus termination reason `LEGACY_END_REASON_UNKNOWN`. Update every existing points/rank aggregate (Player Info, member leaderboard, operational overview/export) to include `COMPLETED` and `LEGACY_SETTLED`, while leaving `ABORTED` excluded. Do not change modern settlement behavior.

5. **Correlate end messages conservatively and disclose protocol limits.** Match `cmd=3` by source IP, game ID and participant UID set against the one open persisted session; require one non-negative point entry per participant with no duplicates or omissions. Record a stable fingerprint of an accepted end payload so a repeated callback after settlement becomes a no-op. Do not guess when no unique open session matches. Since the legacy body has no session/event identifier, a delayed duplicate that arrives during a later, otherwise identical game cannot be distinguished from that game's real end callback; document this residual ambiguity and test the safe rejection/no-award paths that are distinguishable.

6. **Keep room state honest and ephemeral where it is not business data.** A `cmd=1` updates in-memory room presence keyed by source IP and a last-seen timestamp; a 60-second timeout (three expected 20-second intervals) marks it offline. Merge the projection with existing saved room names. `cmd=2` shows the reported game and admitted participants; accepted `cmd=3` clears the active-game projection. On process restart, business sessions remain in SQLite, but rooms are offline until their next heartbeat. Heartbeats and callbacks do not prove controller/floor health, real-time score, or queue state.

7. **Treat `isAdmin` as legacy session metadata, not a credential.** The available decompiled game backend shows that `/game/start` receives the Boolean from its caller, passes it to the pre-start callback, and copies it into the runtime context; the callback is still sent under the normal `hasTouch`/console-IP/launch-method conditions. The game backend does not branch around `cmd=5` based on this flag. Preserve the value on accepted session/result metadata, but always apply current member/wristband eligibility and never grant access based only on `isAdmin=true`. The old center's server-side interpretation cannot be inferred from the game-client source and is not recreated as an authorization bypass. Do not persist or log raw UID values in diagnostic logs; use encrypted result/session storage consistent with the existing protected-data rules.

8. **Respond according to what the old caller actually reads.** `/dev/ping` returns `{"data":"pong"}`. `cmd=5` uses business `code=200` only when all supplied UIDs are admitted and returns a non-200 business code otherwise, because the legacy game blocks launch unless it sees exactly 200. `cmd=1/2/3` get prompt JSON acknowledgements; a business rejection never changes member/point data. `cmd=2/3` do not wait for external side effects and do not claim that the legacy client retries.

## Risks / Trade-offs

- [The legacy protocol is unauthenticated and allows a LAN caller to submit an old-format final score] → Preserve the old wire contract, avoid exposing the new endpoint beyond the intended store network, apply the existing server bind/firewall policy, validate every participant against an open session, and make no award for ambiguous callbacks.
- [Source IP is the only room identity; NAT/shared proxy addresses can merge terminals] → Document one distinguishable source IP per legacy room and never trust caller-supplied `X-Forwarded-For` for room identity.
- [No legacy session/event ID means exact deduplication is impossible for a late duplicate that overlaps a later identical game] → Persist internal session IDs and payload fingerprints, handle ordinary ordered callbacks idempotently, and surface ambiguous/mismatched callbacks without guessing.
- [Legacy result point values can exceed modern score-policy integer assumptions] → Store the old final awarded points as SQLite 64-bit integers without routing them through the modern `Integer` scoring policy; reject out-of-range values rather than truncating.
- [Port 16668 may already be occupied] → Make the port a test/deployment property with the legacy-required default and emit a specific diagnostic naming the port and process-binding problem.
- [Legacy settlement reason is unknown] → Display a distinct legacy-settled marker in records and include its reported points in totals without labeling it as natural completion.

## Migration Plan

1. Add an idempotent SQLite migration for legacy session/callback metadata and any schema status support, preserving existing records.
2. Add protocol adapter, secondary connector and room projection, then run protocol, service-integration, migration, duplicate-callback, restart-recovery and two-port integration tests against a temporary SQLite database.
3. Package the member-admin backend with the new connector enabled by default. Existing databases require no manual conversion; old clients begin to appear after their first heartbeat. Rollback is the previous application build; the new nullable metadata/table can remain unused. Do not drop or rewrite modern records on rollback.
