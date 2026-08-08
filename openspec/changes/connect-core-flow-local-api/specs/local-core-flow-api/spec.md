## ADDED Requirements

### Requirement: Single local persistence owner
The system SHALL keep the SQLite database accessible only to the local Spring Boot backend, and both frontend applications SHALL use HTTP API calls for member and wristband data.

#### Scenario: Frontend reads shared state
- **WHEN** registration-kiosk requests a wristband by UID
- **THEN** the backend reads SQLite and returns the current state and purchased minutes
- **AND** the frontend does not open or mutate the SQLite file directly

### Requirement: Keyboard-wedge UID capture
The charge and wristband-binding steps SHALL accept a numeric UID typed by the reader and terminated by Enter, preserving the UID as text.

#### Scenario: Reader sends the confirmed UID
- **WHEN** the reader types `2283055618` and sends Enter into the focused UID field
- **THEN** the UI submits UID `2283055618` to the backend without inventing or substituting another UID

### Requirement: Counter charges a wristband
The backend SHALL let member-admin charge a scanned wristband with a positive integer minute count and persist it as `CHARGED`.

#### Scenario: Charge an available wristband
- **WHEN** member-admin submits UID `2283055618` and a positive duration
- **THEN** the backend creates or updates that physical UID and stores the purchased minutes
- **AND** the wristband has no member binding yet

#### Scenario: Reject an unavailable wristband
- **WHEN** member-admin tries to charge a wristband that is already READY or ACTIVE
- **THEN** the backend rejects the request with a stable error
- **AND** it does not overwrite the existing binding

### Requirement: Kiosk finds or creates member before scanning
The registration-kiosk SHALL query a member by phone before it starts the wristband scan step, and SHALL create a new member when no active member exists and the registration form is valid.

#### Scenario: Existing member
- **WHEN** the kiosk submits an existing phone number
- **THEN** the backend returns the member profile
- **AND** the kiosk asks the customer to confirm that profile before scanning a wristband

#### Scenario: New member
- **WHEN** the kiosk submits a phone number that has no active member and valid registration details
- **THEN** the backend creates an ACTIVE member
- **AND** the kiosk proceeds to the wristband scan step

### Requirement: Bind charged wristband after member identity
The backend SHALL bind a `CHARGED` wristband to the confirmed member after the kiosk scan and SHALL move it to `READY` without starting timed play.

#### Scenario: Successful binding
- **WHEN** the kiosk submits member ID and scanned UID `2283055618`
- **AND** the wristband is `CHARGED` with purchased minutes
- **THEN** the backend creates the binding and moves the wristband to `READY`
- **AND** the response includes the member, UID, and purchased minutes

#### Scenario: Uncharged or already bound UID
- **WHEN** the kiosk submits a UID that is not `CHARGED`
- **THEN** the backend rejects the bind with a user-readable error
- **AND** no new member-wristband binding is created

### Requirement: First game swipe starts timing
The kiosk binding operation SHALL NOT start the timer; a future game-client operation SHALL be the transition from `READY` to `ACTIVE`.

#### Scenario: Kiosk success does not consume time
- **WHEN** the kiosk finishes binding a wristband
- **THEN** the wristband remains `READY`
- **AND** the response states that timing begins only at the first game-system swipe
