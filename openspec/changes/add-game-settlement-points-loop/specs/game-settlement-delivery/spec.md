## ADDED Requirements

### Requirement: Terminal results are durably captured before lifecycle handoff
The game backend SHALL persist an immutable settlement delivery record locally before it promotes a queued game or releases the completed runtime session.

#### Scenario: A game completes naturally
- **WHEN** the runtime reaches natural success or natural failure
- **THEN** the game backend stores the platform play ID, terminal reason, success flag, raw score, result payload, and delivery state before queue promotion begins

#### Scenario: A game is manually stopped or aborts
- **WHEN** a started member game terminates by manual stop, startup abort, or runtime failure
- **THEN** the game backend durably records the non-awarding terminal result before lifecycle cleanup continues

### Requirement: Settlement delivery survives transient platform failure
The game backend SHALL deliver pending settlements at least once, SHALL retry only retryable transport or service failures with bounded backoff, and SHALL resume pending work after process restart.

#### Scenario: Platform is unavailable at completion
- **WHEN** the result is durably captured but the member platform cannot be reached
- **THEN** the local delivery remains pending, the completed result is not discarded, and a later retry submits the same immutable payload

#### Scenario: Game backend restarts with pending settlements
- **WHEN** the game backend starts and its local store contains undelivered settlement records
- **THEN** it resumes delivery without requiring the game session or Electron client to remain open

#### Scenario: Platform rejects a non-retryable result
- **WHEN** the platform returns a stable validation or business rejection
- **THEN** automatic retry stops for that delivery and diagnostics retain the error code and payload identity for operator action

### Requirement: Settlement delivery is duplicate safe
The game backend SHALL use the platform play identity and one immutable terminal payload for every delivery attempt, and the platform SHALL return the stored terminal record for repeated settlement.

#### Scenario: HTTP response is lost after platform commit
- **WHEN** the platform commits a settlement but the game backend does not receive the response
- **THEN** retrying the same platform play ID returns the existing settlement and does not award points again

#### Scenario: Duplicate runtime terminal callback occurs
- **WHEN** the same game context emits its terminal callback more than once
- **THEN** at most one local settlement delivery identity is created

### Requirement: Delivery health is observable
The game backend SHALL expose delivery counts and sanitized details for pending and permanently failed settlements through a local diagnostic contract.

#### Scenario: Operator inspects a pending delivery
- **WHEN** a settlement is waiting for retry
- **THEN** diagnostics show its platform play ID, state, attempt count, next attempt time, and last stable error code without exposing unrelated member data

