# Game terminal and member platform room connection

The member platform identifies a game room by the source IP of its WebSocket connection. `deviceId` and `roomId` are optional compatibility metadata; they are not required terminal configuration.

## Configuration

On the member platform server:

```text
LED_ROOM_CONNECTION_ENABLED=true
```

On each game terminal backend, configure the member platform address:

```text
MEMBER_PLATFORM_BASE_URL=http://<member-platform-ip>:8090
LEDGAME_DEVICE_ID=<optional compatibility identifier>
LEDGAME_ROOM_ID=<optional compatibility identifier>
LEDGAME_ROOM_NAME=<optional legacy display name>
LED_ROOM_CONNECTION_ENABLED=true
LED_ROOM_RECONNECT_DELAY=5s
```

The current MVP intentionally trusts terminals on the store LAN and does not use a connection token. The game desktop Settings tab only needs the member platform host and port.

Reserve DHCP leases for the member platform and each game terminal. Stable leases prevent an IP change from creating a new room identity.

## Connection and events

The game terminal connects to `/ws/rooms`, sends `HELLO`, and then sends a complete `ROOM_SNAPSHOT`. Game start, queue changes, and game end send `GAME_STARTED`, `QUEUE_CHANGED`, and `GAME_ENDED`. On reconnect, the latest pending event is retained and a fresh snapshot is sent after the welcome response.

## Room names

Member Admin displays the observed IP and allows an administrator to rename the room card. Names are stored in SQLite and remain visible while the terminal is offline or after reconnecting:

```text
PUT /api/rooms/<ip>
{"roomName":"North table"}
```

## Troubleshooting

1. If a room is `OFFLINE`, verify that the game terminal can reach `<member-platform-ip>:8090` and that the configured port is correct.
2. If no room appears, verify `LED_ROOM_CONNECTION_ENABLED` and inspect the game backend connection log.
3. If a room is duplicated, check whether the terminal received a different DHCP address or whether two backends are running on the same machine.
4. A connection interruption does not change member balance, queue, or game records. The terminal retries with backoff and publishes its current snapshot after reconnecting.
