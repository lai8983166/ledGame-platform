## Context

The two Vue applications run as separate processes on the store computer. The reader behaves as a keyboard wedge: it types a numeric UID and then presses Enter. The existing UI has no persistence or backend connection, so each app sees unrelated demo state.

## Goals / Non-Goals

**Goals:**

- Make the Spring Boot server the only process that opens the SQLite file.
- Expose the minimum HTTP operations needed by member-admin and registration-kiosk.
- Preserve the real store order: admin charges first; kiosk finds/creates the member, then scans and binds the charged wristband.
- Store UID as text so leading zeroes are preserved, while accepting the confirmed numeric reader output format.

**Non-Goals:**

- No direct SQLite access from either frontend.
- No USB/serial reader SDK; keyboard-wedge capture is sufficient.
- No payment integration, authentication, cloud sync, or game-client implementation in this change.
- No invented UID buttons or fake state as the core path.

## Decisions

1. **SQLite through Spring JDBC**: use the Xerial SQLite JDBC driver and `JdbcTemplate` instead of JPA. This keeps the local server small and makes the schema and transaction boundaries explicit.
2. **Local HTTP API**: both frontends call `http://127.0.0.1:8090/api`. The SQLite file remains private to the server process, avoiding cross-process database locking and duplicated state.
3. **UID as numeric text**: normalize only whitespace and the reader's Enter terminator. Persist the exact digit string in a unique `TEXT` column; do not convert it to a number.
4. **Core state model**: `IN_STOCK` → `CHARGED` → `READY` → `ACTIVE` → `EXPIRED`. The kiosk bind operation produces `READY`; a later game-client first swipe will be responsible for `ACTIVE`.
5. **Atomic bind**: member lookup/creation and charged-wristband binding are separate API calls, but the final bind updates the wristband and creates its binding record in one transaction.

## Risks / Trade-offs

- **[Reader focus]** A keyboard-wedge reader sends keystrokes to the focused element. → The UID field is focused when the charge/scan step opens, and Enter submits the scan.
- **[UID format variation]** Some readers may include a prefix or suffix. → The first implementation accepts digits only and keeps a clear validation error; a sample format change can be handled in one normalizer.
- **[Local server availability]** A stopped backend makes both clients unusable for core operations. → Show an explicit connection error and keep the server as a single local process launched with the apps.

## Migration Plan

1. Add the SQLite dependency and create the schema on server startup.
2. Start the local server before either frontend.
3. Replace frontend demo mutations with API calls and remove fake UID controls.
4. Existing demo data is not migrated; a fresh local database is created for this MVP.

## Open Questions

- The future game client still needs its own first-swipe endpoint to transition `READY` to `ACTIVE`; this change only prepares the state.
