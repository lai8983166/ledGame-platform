## Why

Small stores should not manually maintain multiple terminal identity fields. The member platform already observes the game terminal's source IP, so that IP should be the room identity. The game desktop only needs the member platform address.

## What Changes

- Use the observed WebSocket source IP as the room key.
- Make `deviceId` and `roomId` optional compatibility metadata.
- Persist administrator-defined room names by IP in SQLite.
- Add room rename support to Member Admin.
- Add member platform host and port settings to the game desktop.
- Remove connection-token configuration and authentication from the MVP; terminals are trusted within the store LAN.
- Preserve automatic reconnect and snapshot recovery.

## Impact

This affects the platform room registry/API, the Member Admin room cards, the game backend connection client, the Electron settings UI, and their tests/documentation.
