## 1. Member platform room identity

- [x] 1.1 Add SQLite room settings table keyed by normalized source IP with display name and timestamps.
- [x] 1.2 Make `deviceId` and `roomId` optional in HELLO and use source IP as the canonical room key.
- [x] 1.3 Add room rename API with validation and merge persisted names into online/offline room projections.
- [x] 1.4 Add server tests for IP-only connections, reconnect fencing, rename persistence, and offline lookup.

## 2. Game backend compatibility and runtime configuration

- [x] 2.1 Make terminal identity metadata optional for connection payloads while preserving existing game record fields.
- [x] 2.2 Add validated runtime member-platform configuration model and reconnect operation to `RoomConnectionClient`.
- [x] 2.3 Add local backend GET/PUT/test endpoints for member platform host and port.
- [x] 2.4 Add backend tests for validation, configuration replacement, reconnect, and snapshot recovery.

## 3. Game desktop settings UI

- [x] 3.1 Extend Electron application settings persistence and preload IPC with member platform connection fields.
- [x] 3.2 Inject saved settings when starting the embedded backend and apply changes to a running backend.
- [x] 3.3 Add member platform host/port fields, validation, test connection action, and status display to the Settings Tab.
- [x] 3.4 Add frontend and Electron unit tests for migration of old settings, validation, save, and reconnect feedback.

## 4. Admin room naming UI and shared client

- [x] 4.1 Add room rename support to the platform API client and member-admin room card UI.
- [x] 4.2 Show IP as the room identity and keep names stable across polling, disconnects, and reconnects.
- [x] 4.3 Add member-admin tests for rename success, invalid names, and offline room persistence.

## 5. Verification and rollout

- [x] 5.1 Run platform, backend, Electron, and frontend test suites with the new configuration flow.
- [ ] 5.2 Run a local three-process smoke test: configure platform address in the game UI, connect, rename room, restart backend, and verify reconnection.
- [x] 5.3 Update deployment documentation with per-terminal settings, DHCP reservation guidance, and rollback behavior.

## 6. Remove unnecessary MVP authentication

- [x] 6.1 Remove token validation and token requirements from the member platform WebSocket handshake.
- [x] 6.2 Remove token persistence, environment variables, IPC payloads, and backend configuration fields.
- [x] 6.3 Remove the token field from the game desktop Settings Tab and update migration/validation tests.
- [x] 6.4 Update specifications and deployment documentation to describe trusted-LAN operation without tokens.
