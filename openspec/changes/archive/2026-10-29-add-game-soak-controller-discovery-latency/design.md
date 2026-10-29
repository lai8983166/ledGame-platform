## Context

The soak harness already samples `/hardware/elc408/metrics` every 10 seconds, while the backend SDK owns the UDP socket, NIC selection, discovery packet construction, and 0x68 reply parsing. A probe must therefore run inside the SDK process so its latency excludes HTTP and test-runner scheduling overhead. See `proposal.md` and the capability spec for the observable contract.

## Goals / Non-Goals

**Goals:**

- Reuse the existing SDK search transaction and configured network interface.
- Measure first-search-packet-to-first-unique-valid-reply latency with a monotonic clock.
- Expose only cumulative counters and totals; do not persist raw probe timestamps.
- Keep the normal product path unchanged unless the acceptance probe is explicitly enabled by the isolated soak runtime.
- Include the aggregate result in existing soak communication evidence and reports.

**Non-Goals:**

- No controller health daemon or always-on production polling.
- No per-packet ACK/loss claim; the protocol still does not provide a reliable ACK.
- No new database tables, UI, or changes to normal game discovery behavior.
- No probe in simulated mode.

## Decisions

1. **Gate the backend endpoint with an acceptance-only Spring property.** The isolated runtime sets `ledgame.acceptance.search-probe-enabled=true` only for real-hardware runs. The endpoint returns a stable disabled error otherwise. This avoids silently adding discovery traffic to customer sessions while keeping the packaged test self-contained.

2. **Trigger the endpoint from the existing 10-second sampler.** The sampler calls the endpoint once per post-start sample, then reads cumulative SDK metrics. This avoids another scheduler and keeps probe/report cadence aligned with existing process and communication samples. The first immediate startup sample is read-only; the first probe occurs at the first 10-second interval.

3. **Instrument `SearchSession` and `Elc408Metrics`.** The session records only a monotonic start value and a per-session set of controller keys needed for duplicate suppression. On the first valid reply for each controller, it sends the elapsed milliseconds to metrics and discards the timing value. Metrics retain only attempts, unique response samples, timeouts, and latency total/average. A no-reply search increments a timeout after the existing bounded wait.

4. **Use the SDK's existing search method for the probe.** The probe calls the configured-interface search path, preserving the current three 0x67 sends and 0x68 validation. This ensures real acceptance measures the same protocol path used by the product and does not create a second UDP implementation.

5. **Extend, rather than replace, communication aggregation.** The soak monitor treats the new metric fields as cumulative counters and derives the final average from deltas. Existing send/receive/parse fault status remains authoritative; discovery latency is supporting evidence and does not by itself claim packet loss.

## Risks / Trade-offs

- [Probe briefly shares the SDK search lock with RGB sends] → The existing bounded search window is retained; probes occur only every 10 seconds and are explicitly part of real-hardware acceptance.
- [A multi-controller transaction produces more than one latency sample] → Count only the first valid reply per controller key for that transaction and report the sample count.
- [A controller is silent] → Bound the existing wait, count a timeout, exclude it from the average, and continue the run.
- [Older backend/game packages lack the endpoint] → Real soak sampling surfaces a clear request failure; the packaged build process must include the updated backend before running this capability.

## Migration Plan

No persistent migration is needed. Build the updated game backend and soak package together. Existing runs and reports remain readable; older metric snapshots without the new fields are treated as missing discovery evidence rather than as a communication fault.
