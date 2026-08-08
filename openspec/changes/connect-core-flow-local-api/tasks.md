## 1. Local backend and SQLite

- [x] 1.1 Add SQLite JDBC and Spring JDBC dependencies and configure a local database path.
- [x] 1.2 Create SQLite schema and seed only empty tables, with unique member phone and wristband UID constraints.
- [x] 1.3 Implement repositories/services for members, wristbands, and bindings with the CHARGED → READY core transition.
- [x] 1.4 Add local HTTP endpoints for member lookup/create, wristband charge, wristband lookup, wristband bind, and list.
- [ ] 1.5 Add backend tests for UID `2283055618`, duplicate UID, unavailable state, and atomic bind behavior.

## 2. Member-admin integration

- [x] 2.1 Replace in-memory wristband charge with the backend API and numeric reader UID input ending in Enter.
- [x] 2.2 Load the wristband table from the backend and remove invented UID controls from the core path.
- [x] 2.3 Surface backend validation and connection errors in the charge/bind UI.

## 3. Registration-kiosk integration

- [x] 3.1 Query/create the member through the backend before showing the wristband scan step.
- [x] 3.2 Capture the real reader UID and bind it through the backend after member confirmation.
- [x] 3.3 Remove demo UID buttons and show purchased minutes returned by the backend.
- [x] 3.4 Keep kiosk success in READY state and explain that the first game swipe starts timing.

## 4. Verification

- [ ] 4.1 Run backend tests and confirm the SQLite file is the only persistence source.
- [x] 4.2 Run frontend type checks and production builds.
- [ ] 4.3 Verify the end-to-end path: charge `2283055618` → kiosk find/create member → scan → bind → READY.

## 5. Admin data integrity actions

- [x] 5.1 Return the exact “此手环已绑定” message when a READY or ACTIVE wristband is submitted again.
- [x] 5.2 Load the member-admin member list from the backend member table instead of frontend seed data.
- [x] 5.3 Add backend-backed actions to clear an unbound charged balance and to unbind a READY wristband back to inventory.
