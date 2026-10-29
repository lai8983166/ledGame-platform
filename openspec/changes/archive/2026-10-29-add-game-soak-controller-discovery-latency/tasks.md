## 1. Backend discovery timing and gated probe

- [x] 1.1 Extend ELC-408 metrics with cumulative discovery attempts, unique valid response samples, timeouts, and latency total/average without storing raw timestamps; verify with backend unit tests.
- [x] 1.2 Instrument the SDK search session to mark the first 0x67 send, measure monotonic elapsed time for each first valid controller reply, suppress duplicate replies per transaction, and count no-reply timeouts; verify with search/session tests.
- [x] 1.3 Add an acceptance-only `/hardware/elc408/acceptance/search-probe` endpoint that invokes the configured-interface SDK search and returns stable disabled/SDK failure errors; verify with controller API tests.
- [x] 1.4 Enable the probe property only for real-hardware isolated soak runtimes and keep simulated runtimes disabled; verify isolation configuration tests.

## 2. Soak aggregation and reporting

- [x] 2.1 Trigger one probe at each post-start 10-second sampling interval for real hardware, then sample cumulative metrics; keep the startup sample read-only and leave simulated runs unprobed; verify sampling tests.
- [x] 2.2 Extend communication aggregation to calculate average response latency from cumulative deltas and preserve existing fault/status judgments; verify monitor tests for replies, duplicates, timeouts, missing fields, and resets.
- [x] 2.3 Add discovery attempt/sample/timeout/average fields to JSON and Chinese Markdown acceptance reports without individual timestamps; verify report tests.

## 3. Validation and packaging

- [x] 3.1 Run backend ELC-408 tests and the complete game-soak test suite, including simulated regression coverage; verify all tests pass.
- [x] 3.2 Validate this OpenSpec change and rebuild the portable soak package so the updated probe/report logic is shipped; verify `openspec validate --strict` and the portable build output.
