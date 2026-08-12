# Acceptance test-first record

The first golden-path execution is intentionally expected to fail before any testability seam is added.

- Scenario: `store golden path: charge, bind, play, queue, promote, and inspect`
- First boundary: `ACCEPTANCE_STARTUP_NOT_IMPLEMENTED`
- Missing capability at that point: browser clients cannot receive a run-owned platform API endpoint, so an isolated multi-process run cannot safely start.
- Required next step: implement validated endpoint injection and isolated service configuration before replacing the startup stub.
