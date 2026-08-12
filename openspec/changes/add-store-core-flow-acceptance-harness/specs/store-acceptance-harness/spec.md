## ADDED Requirements

### Requirement: Acceptance runs use an isolated store environment
The harness SHALL start every required client and service with test-owned ports, test-owned storage, and deterministic fixture identities, and SHALL leave operator databases and configuration unchanged.

#### Scenario: Start a clean acceptance run
- **WHEN** a developer invokes the store acceptance command
- **THEN** the harness starts the platform server, both platform clients, the game backend, and the Electron game client against disposable storage
- **AND** the run does not read from or write to the normal operator database paths

#### Scenario: Repeat the same acceptance run
- **WHEN** the acceptance command is run repeatedly after a pass, failure, or interrupted prior run
- **THEN** each run begins from the same declared fixture state without depending on data left by an earlier run

### Requirement: The golden path is driven through real client surfaces
The acceptance suite SHALL drive Member Admin, Registration Kiosk, and the Electron game client through their normal UI and IPC/network boundaries rather than mutating their stores or databases directly.

#### Scenario: Complete the store golden path
- **WHEN** the golden-path scenario runs
- **THEN** Member Admin charges a known wristband through its visible workflow
- **AND** Registration Kiosk looks up or creates a member and binds the scanned wristband
- **AND** the game client admits that wristband from the game configuration flow and starts a simulated game
- **AND** a second eligible wristband is queued while the first game is active
- **AND** ending the game through the Debug Panel promotes and starts the queued game
- **AND** Member Admin and Player Info display the resulting authoritative room, member, wristband, balance, and play-record state

### Requirement: Physical devices are replaced only at controlled input boundaries
The automated suite SHALL run without a physical reader, LED controller, or floor while preserving the same application paths used by those devices in production.

#### Scenario: Simulate a wristband reader
- **WHEN** a scenario scans a wristband
- **THEN** the harness types the declared numeric UID followed by Enter into the focused client
- **AND** the client processes it through its production keyboard-reader path

#### Scenario: Simulate game completion
- **WHEN** the scenario needs to advance or end gameplay
- **THEN** it uses the existing debug simulation controls and production game lifecycle endpoints
- **AND** it does not directly rewrite runtime state or platform records

### Requirement: Services are orchestrated by observable readiness
The harness SHALL wait for explicit readiness from each required process, enforce bounded timeouts, and tear down only the processes and temporary files created for the current run.

#### Scenario: A service fails to become ready
- **WHEN** a required process exits early or does not pass its readiness check before the configured timeout
- **THEN** the run fails at the named startup step
- **AND** all processes started by the harness are stopped without affecting unrelated local services

### Requirement: Acceptance failures produce actionable evidence
The harness SHALL identify the failed business step and retain bounded diagnostics sufficient to distinguish platform, browser client, Electron, game backend, and synchronization failures.

#### Scenario: A cross-client assertion fails
- **WHEN** an expected visible or authoritative state is not reached
- **THEN** the report names the scenario and step
- **AND** it stores relevant screenshots, browser traces, Electron screenshots, and bounded service logs in a run-specific artifact directory
- **AND** secrets and unrelated operator data are not captured

### Requirement: Critical recovery behavior is covered separately
The acceptance suite SHALL provide focused scenarios for business rejection and recoverable connectivity behavior in addition to the golden path.

#### Scenario: Reject invalid repeated or insufficient operations
- **WHEN** a focused scenario repeats a bind or enqueue request or attempts admission with insufficient balance
- **THEN** the client shows the stable business outcome
- **AND** the authoritative platform and game state remains internally consistent

#### Scenario: Recover a game client after platform interruption
- **WHEN** the harness temporarily stops and restarts the isolated platform service while the game client remains open
- **THEN** the game connection retries automatically
- **AND** the room returns online without creating a duplicate room identity or corrupting the current queue

