## Why

The current member-admin and registration-kiosk screens use independent in-memory demo data and invented wristband IDs, so a real reader scan cannot find the minutes charged by the counter or bind the wristband to the member created at the kiosk. The store needs one local backend as the only owner of SQLite while both frontends communicate with it over HTTP.

## What Changes

- Add a minimal local SQLite-backed backend for members, wristbands, and wristband bindings.
- Accept the physical reader's keyboard-wedge UID (`2283055618`-style digits followed by Enter) at the charge and binding steps.
- Let member-admin charge a scanned wristband with purchased minutes through the backend.
- Make registration-kiosk query or create the member first, then scan and bind the charged wristband through the backend.
- Remove invented demo UID controls and cross-frontend in-memory state from the core path.
- Keep first game-system swipe as the event that starts timed play; kiosk binding only makes the wristband ready.

## Capabilities

### New Capabilities

- `local-core-flow-api`: Shared local API and SQLite persistence for the member/wristband flow.

### Modified Capabilities

- None.

## Impact

- `server/`: SQLite dependency, schema, repositories/services, and local HTTP endpoints.
- `apps/member-admin`: charge and list wristbands through the API; capture reader UID input.
- `apps/registration-kiosk`: query/create members before reader scan; bind the scanned UID through the API.
- `packages/api-client`: shared typed request helper may be extended for the local endpoints.
