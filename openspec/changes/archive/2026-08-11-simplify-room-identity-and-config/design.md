## Context

The platform already observes the source IP of every room WebSocket connection. Requiring operators to configure device and room identifiers, plus a shared token, adds setup work without helping the current single-store MVP.

## Decisions

1. **Source IP is the room key.** `deviceId` and `roomId` remain optional payload metadata for compatibility.
2. **Room names are platform data.** SQLite stores a display name keyed by normalized IP; list and detail APIs merge it into online and offline projections.
3. **The game desktop stores only host and port.** Electron persists these values, injects them into the embedded backend, and exposes a connection test.
4. **The MVP trusts the store LAN.** The WebSocket `HELLO` handshake does not require a token. No token is stored, logged, or exposed in settings or environment variables.
5. **Reconnect behavior is preserved.** A valid address change closes the old socket and schedules an immediate reconnect; normal backoff and snapshot recovery remain unchanged.

## Trade-offs

- A DHCP address change creates a new room identity, so stores should reserve DHCP leases.
- Any device able to reach the platform WebSocket can submit room events. Authentication can be added later if remote or cross-store deployment is introduced.
