# Spec Delta

## MODIFIED Requirements

### Requirement: First valid game swipe activates purchased time
The platform SHALL activate a READY binding using server time and consume the member's available time account. Issuance alone SHALL NOT start consumption; after return the next activation SHALL resume only the preserved member balance.

#### Scenario: Activate a ready wristband
- **WHEN** a game backend admits a READY wristband with positive member balance
- **THEN** account consumption begins and the original response fields include member, binding, expiry and remaining seconds

#### Scenario: Activation does not depend on game selection
- **WHEN** a valid wristband is admitted before game selection
- **THEN** the existing continuous consumption rule applies until return or expiry, including preparation cancellation

### Requirement: Remaining balance is server derived
The platform SHALL derive remaining time from persisted member balance and consumption start, never from client supplied time or a former wristband's entitlement.

#### Scenario: Client requests current balance
- **WHEN** a caller requests admission or player information for a READY or ACTIVE binding
- **THEN** the original access fields report server-derived member balance, status and expiry without trusting a client balance

#### Scenario: Account queried after return
- **WHEN** the returned member is queried later or is issued another wristband
- **THEN** remaining balance is unchanged during the paused period and the old wristband is not entitled to use it
