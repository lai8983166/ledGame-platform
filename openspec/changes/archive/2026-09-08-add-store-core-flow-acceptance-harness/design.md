## Context

The platform server already has a real-SQLite API smoke test that covers registration through settlement and Player Info. The game backend has focused member-platform contract, queue, lifecycle, room-connection, and simulation tests. Member Admin, Registration Kiosk, and the Electron renderer also have unit or source-contract tests. What is missing is proof that independently started production-shaped processes and real user interfaces agree on the same state.

The developer may not be present at deployment sites, so the harness must reproduce the store's essential software topology locally and fail with useful evidence. It must run on Windows, require no reader/controller/floor hardware, avoid operator data, and remain deterministic enough to be useful during test-first changes.

## Goals / Non-Goals

**Goals:**

- Provide one command that starts and owns an isolated five-process store topology.
- Drive one high-value golden path through real browser and Electron surfaces.
- Reuse the existing keyboard wristband reader and game debug simulation path.
- Make ports, platform endpoints, database paths, and runtime paths injectable for tests.
- Produce step-level traces, screenshots, and bounded logs on failure.
- Keep the existing fast unit/integration suites as the normal development feedback loop.

**Non-Goals:**

- Validating electrical behavior of the USB reader, ELC-408 controller, LED output, speakers, or physical floor.
- Proving installer, firewall, router, or arbitrary store LAN configuration in this change.
- Translating or cleaning all demo UI copy.
- Exhaustively testing every page, game type, or visual layout.
- Replacing focused unit, API integration, and backend lifecycle tests with UI tests.

## Decisions

1. **The platform repository owns the acceptance runner.** The workflow starts with member/wristband data and observes all clients, so `ledGame-platform` provides `pnpm test:acceptance`, the Playwright configuration, fixtures, orchestration, and report location. The sibling game repositories remain independently buildable and expose only the configuration and stable surfaces needed by the runner. Duplicating a runner in every repository was rejected because it would fragment lifecycle ownership and diagnostics.

2. **Use Playwright Test for both browser clients and Electron.** Chromium contexts drive Member Admin and Registration Kiosk; Playwright's Electron support launches and controls the game desktop. This gives one assertion, timeout, trace, screenshot, and retry model across the three user surfaces. Pure HTTP scripting was rejected because the existing API smoke test already covers that layer and cannot catch focus, stale rendering, keyboard scan, or IPC problems.

3. **Allocate test-owned runtime resources per run.** A Node orchestration layer creates a unique temporary directory, allocates or reserves loopback ports, and injects those values into all child processes. The platform SQLite URL points inside that directory; game backend persistent/runtime data also points to test-owned paths. Reusing development databases or requiring manual cleanup was rejected because it makes tests order-dependent and risks operator data.

4. **Configuration is injected through documented test-safe seams.** Browser clients resolve their platform base URL from environment/runtime configuration with the existing loopback default preserved. The game backend and Electron main process receive platform/game endpoints and data paths through validated environment or command-line configuration. The tests SHALL not patch compiled bundles or intercept production APIs with fake responses for the golden path.

5. **The harness owns process lifecycle and uses readiness probes.** It launches child processes without opening extra terminal windows, streams bounded logs with process labels, waits on existing health/state endpoints plus page readiness, and terminates only recorded child process trees in reverse order. Fixed sleeps are allowed only for UI stabilization after an observable state transition, not for service startup.

6. **Use production input paths with deterministic test controls.** Wristband scans are numeric keyboard input followed by Enter. Game progression is driven through the existing Debug Panel and backend simulation mode. Direct database inserts are limited to declared initial fixture setup when no public preparation API exists; all golden-path business transitions occur through visible UI actions and normal service contracts.

7. **Assertions follow authoritative state across boundaries.** Each step first asserts the user-visible state that makes the workflow operable, then uses public API state only for precise diagnostics or final cross-client consistency. Tests do not read internal Vue state, Electron globals, or database tables. Stable `data-testid` attributes identify controls and state without coupling to translated labels or CSS layout.

8. **Start with one serial golden path.** The first suite uses fixed logical identities (including wristband UID `2283055618`) inside a fresh database and runs serially. Focused rejection/recovery scenarios each receive their own isolated run. Parallel multi-store execution and broad browser matrices are deferred until the single Windows/Chromium/Electron path is reliable.

9. **Failure artifacts are run-scoped and bounded.** The runner stores Playwright traces, screenshots, step metadata, and the tail of each child-process log under an ignored artifacts directory. Successful runs remove disposable storage by default; a diagnostic flag may preserve it. Logs are filtered through the applications' existing safe logging policies.

## Risks / Trade-offs

- [Cross-repository startup can be slow and fragile] → Keep `test:core` and `test:e2e` fast; reserve `test:acceptance` for core-flow completion and pre-delivery checks, and use readiness probes instead of sleeps.
- [Electron focus affects keyboard-reader tests] → Bring the target window to the foreground, click an explicit scan surface before typing, and assert the scanned UID before continuing.
- [Dynamic ports may expose hard-coded endpoint assumptions] → Add one validated configuration seam per client while preserving current defaults and cover it with focused tests.
- [UI selectors can turn tests into implementation snapshots] → Add selectors only to business actions and authoritative state, and assert outcomes rather than DOM structure.
- [Process teardown on Windows can leak Java/Node children] → Track process trees by launch handle, use bounded graceful shutdown followed by scoped force termination, and never kill by broad executable name.
- [The simulated path cannot prove physical hardware] → Maintain a separate short on-site hardware checklist; treat automated acceptance as software integration coverage, not hardware certification.

## Migration Plan

1. Add the runner dependency and a failing golden-path test that documents the intended steps.
2. Add isolated configuration seams, stable selectors, and readiness endpoints/tests in the affected repositories.
3. Implement process orchestration and temporary-storage lifecycle.
4. Make the golden path pass using keyboard scan and debug simulation.
5. Add focused rejection, reconnect, and idempotency scenarios.
6. Document prerequisites and artifact locations, then run focused suites, full repository suites, and repeated acceptance runs.

Rollback removes the acceptance command and test-only configuration seams; no production database migration or API rollback is required.

## Open Questions

- The initial implementation should confirm whether the existing game backend readiness endpoint is sufficient or whether a narrow health endpoint is required.
- The implementation should measure the stable Windows runtime before choosing the default acceptance timeout; individual state waits remain shorter and step-specific.
