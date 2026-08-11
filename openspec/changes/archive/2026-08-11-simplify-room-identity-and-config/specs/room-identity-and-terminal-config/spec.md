## ADDED Requirements

### Requirement: Source IP identifies a room
The member management server SHALL identify a connected game terminal room by the observed WebSocket source IP, and SHALL accept connections when optional `deviceId` or `roomId` fields are absent.

#### Scenario: Terminal connects without credentials
- **WHEN** a game terminal sends a valid `HELLO` without credentials, `deviceId`, or `roomId`
- **THEN** the server accepts the connection and exposes the observed source IP in the room projection

#### Scenario: Same IP reconnects
- **WHEN** a second connection arrives from an IP that already has an active room connection
- **THEN** the previous connection is fenced and only the newest connection remains online

### Requirement: Room display names are persistent
The member management server SHALL persist a room display name keyed by source IP and SHALL merge it into room list and detail responses whether the room is online or offline.

#### Scenario: Administrator renames a room
- **WHEN** an authorized room name update is submitted for an observed IP
- **THEN** subsequent room queries return the saved name

#### Scenario: Room disconnects
- **WHEN** a named room disconnects
- **THEN** the room remains queryable as offline with the saved display name

### Requirement: Terminal connection settings are editable
The game desktop settings Tab SHALL allow an operator to edit the member platform host and port, validate the values, save them locally, and show the last connection/test result.

#### Scenario: Save valid connection settings
- **WHEN** the operator enters a valid host and port and saves
- **THEN** the desktop backend receives the new settings and begins connecting to the corresponding member platform WebSocket endpoint

#### Scenario: Invalid port is entered
- **WHEN** the operator enters a non-numeric or out-of-range port
- **THEN** the settings are rejected with a visible validation error and the previous working configuration remains active

### Requirement: Runtime configuration reconnects safely
The game backend SHALL validate a new member platform configuration before replacing the active configuration, close the old room connection after acceptance, and reconnect using the new configuration with a full room snapshot.

#### Scenario: Platform address changes while idle
- **WHEN** valid connection settings are saved while no game is running
- **THEN** the backend reconnects to the new address and publishes a full room snapshot after `WELCOME`

#### Scenario: Connection is interrupted
- **WHEN** the member platform or network becomes unavailable
- **THEN** the backend retries with configured backoff and does not change member balance, game status, or queue state solely because of the disconnect
