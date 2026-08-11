## 1. Protocol and shared contracts

- [x] 1.1 Define room WebSocket message envelopes, connection authentication, connection epoch, event types, and acknowledgement/error codes.
- [x] 1.2 Define room snapshot and event payloads for `ROOM_SNAPSHOT`, `GAME_STARTED`, `QUEUE_CHANGED`, and `GAME_ENDED`, including event sequence/idempotency fields.
- [x] 1.3 Add contract tests covering malformed messages, unsupported event types, duplicate events, and old event sequences.

## 2. Member management backend

- [x] 2.1 Add room connection WebSocket endpoint and authenticate game desktop clients with device credentials.
- [x] 2.2 Resolve the room by the observed connection source IP and enforce one active connection epoch per IP.
- [x] 2.3 Persist or maintain room connection state, last snapshot, last accepted event, connection timestamps, and offline transitions.
- [x] 2.4 Implement snapshot/event handlers with idempotency and sequence ordering.
- [x] 2.5 Add room status query APIs for the member management UI and integration tests for connect, reconnect, disconnect, and event updates.

## 3. Member management UI

- [x] 3.1 Replace static room Card status with the room connection/status projection from the backend.
- [x] 3.2 Display IP, online/offline state, current runtime state, current game, queue length, and last event time.
- [x] 3.3 Add visible handling for unknown IP, duplicate connection, stale snapshot, and offline diagnostic states.
- [x] 3.4 Add UI tests for online, offline, reconnect, and event-driven state transitions.

## 4. Game backend connection client

- [x] 4.1 Implement an outbound WebSocket client in `ledGame-backend` with device credentials and configured member platform URL.
- [x] 4.2 Add reconnect handling, connection epoch tracking, protocol ping/pong or TCP keepalive, and full snapshot publication after reconnect.
- [x] 4.3 Publish `GAME_STARTED`, `QUEUE_CHANGED`, and `GAME_ENDED` at the authoritative game lifecycle and queue transition points.
- [x] 4.4 Add bounded event buffering or retry behavior so a short connection interruption does not lose the next snapshot.
- [x] 4.5 Add backend unit and integration tests for connection lifecycle, message ordering, duplicate acknowledgement, and reconnect snapshot recovery.

## 5. End-to-end verification

- [ ] 5.1 Test one real game desktop connection updating one member management room Card over the local network.
- [ ] 5.2 Test disconnect and reconnect while idle, while a game is running, and while the queue is non-empty.
- [ ] 5.3 Verify that disconnect marks the room offline without automatically ending a game or changing member balance/records.
- [x] 5.4 Document static IP/DHCP reservation, device credential setup, member platform address, and troubleshooting steps.

> 5.1-5.3 are physical store acceptance checks and remain open until a real desktop and member-management machine are connected on the target LAN. The automated WebSocket integration test covers the same connect/event/disconnect projection behavior on localhost.
