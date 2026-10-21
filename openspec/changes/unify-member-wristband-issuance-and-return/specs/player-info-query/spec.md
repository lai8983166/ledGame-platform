# Spec Delta

## MODIFIED Requirements

### Requirement: Player balance reflects current server time
The platform SHALL return unallocated member balance even without a wristband; wristband fields SHALL separately report its allocated remaining seconds and expiry. Successful self-service binding SHALL start continuous allocated timing immediately.

#### Scenario: Query an active wristband
- **WHEN** Player Info includes an ACTIVE wristband
- **THEN** it returns the UID and existing access fields with server-derived allocated remaining time separately from account balance

#### Scenario: Query a ready wristband
- **WHEN** Player Info includes a READY wristband
- **THEN** the member's preserved balance is available and consumption has not resumed yet

#### Scenario: Query without a wristband
- **WHEN** the member has returned the wristband and retains time
- **THEN** the response exposes the paused account balance independently of the wristband list
