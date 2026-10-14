# Spec Delta

## MODIFIED Requirements

### Requirement: Player balance reflects current server time
The platform SHALL return the member account's current available balance even without a current wristband; wristband balance fields SHALL project that member balance for compatibility.

#### Scenario: Query an active wristband
- **WHEN** Player Info includes an ACTIVE wristband
- **THEN** it returns the UID and existing access fields with server-derived member balance

#### Scenario: Query a ready wristband
- **WHEN** Player Info includes a READY wristband
- **THEN** the member's preserved balance is available and consumption has not resumed yet

#### Scenario: Query without a wristband
- **WHEN** the member has returned the wristband and retains time
- **THEN** the response exposes the paused account balance independently of the wristband list
