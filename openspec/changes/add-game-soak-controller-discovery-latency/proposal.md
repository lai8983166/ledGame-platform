## Why

The packaged game soak test currently verifies controller traffic counters but does not measure whether a controller answers discovery requests promptly. A bounded, aggregate latency measurement makes long-running hardware acceptance results more useful without retaining a large timestamp log.

## What Changes

- Add a test-only ELC-408 discovery probe endpoint in the game backend.
- During a real-hardware soak run, issue one discovery transaction every 10 seconds and measure from the first search packet to the first matching controller reply.
- Keep only cumulative response-latency total and sample count (plus attempts/timeouts); report the final average latency in the acceptance summary.
- Keep simulated runs explicitly unverified for this hardware metric and do not change normal game behavior.

## Capabilities

### New Capabilities

- `game-soak-controller-discovery-latency`: periodic controller discovery probes and aggregate average response-latency evidence for real-hardware soak acceptance.

### Modified Capabilities

<!-- No existing product capability requirements change; this is acceptance tooling and a gated backend test endpoint. -->

## Impact

- `ledGame-backend`: ELC-408 metrics, search-session timing, and a gated acceptance endpoint.
- `ledGame-platform/game-soak-acceptance`: sampling, communication summary, reports, and automated tests.
- Real hardware soak runs send the SDK's existing three-packet discovery transaction every 10 seconds; simulated/game behavior remains unchanged.
