# Spec Delta

## MODIFIED Requirements

### Requirement: First valid game swipe activates purchased time
The platform SHALL start the allocated entitlement at counter issuance or successful self-service activation using server time. Game admission SHALL only validate this entitlement and SHALL NOT restart timing or consume the unallocated member account.

#### Scenario: Activate a ready wristband
- **WHEN** a game backend admits an already issued ACTIVE wristband with positive allocated remaining time
- **THEN** the allocated entitlement is validated and original response fields include member, binding, expiry and allocated remaining seconds without resetting its start

#### Scenario: Activation does not depend on game selection
- **WHEN** a valid wristband is admitted before game selection
- **THEN** the existing continuous consumption rule applies until return or expiry, including preparation cancellation

### Requirement: Remaining balance is server derived
The platform SHALL derive current wristband remaining time from persisted allocation and start, never from client supplied time, unallocated balance or a former holder's entitlement.

#### Scenario: Client requests current balance
- **WHEN** a caller requests admission or player information for a READY or ACTIVE binding
- **THEN** the original access fields report server-derived allocated remaining time, status and expiry without trusting a client balance

#### Scenario: Account queried after return
- **WHEN** the returned member is queried later or is issued another wristband
- **THEN** remaining balance is unchanged during the paused period and the old wristband is not entitled to use it
