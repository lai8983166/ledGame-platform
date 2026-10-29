# game-access-entitlement Specification

## Purpose

按服务端时间验证会员当前已分配的手环权益，遵守发卡或自助激活即连续计时、还卡退回剩余及游戏放行不重置计时的规则。

## Requirements

### Requirement: Allocated access begins at issuance or self-service activation
The platform SHALL start the allocated entitlement at counter issuance or successful self-service activation using server time. Game admission SHALL only validate this entitlement and SHALL NOT restart timing or consume the unallocated member account.

#### Scenario: Admit an issued wristband
- **WHEN** a game backend admits an already issued ACTIVE wristband with positive allocated remaining time
- **THEN** the allocated entitlement is validated and original response fields include member, binding, expiry and allocated remaining seconds without resetting its start

#### Scenario: Activation does not depend on game selection
- **WHEN** a valid wristband is admitted before game selection
- **THEN** the existing continuous consumption rule applies until return or expiry, including preparation cancellation

### Requirement: Active wristband admission is idempotent
The platform SHALL return the existing active window for an `ACTIVE` binding without resetting or extending its start time.

#### Scenario: Scan an active wristband again
- **WHEN** a game backend submits an `ACTIVE` wristband before its expiry
- **THEN** the platform returns the original `started_at`, the same calculated expiry, and the newly calculated remaining seconds

### Requirement: Ineligible wristbands fail closed
The platform SHALL deny game access for wristbands that are unknown, unbound, expired, associated with an ineligible member, or otherwise not entitled to play, using stable error codes.

#### Scenario: Charged but unbound wristband
- **WHEN** a game backend submits a wristband in `CHARGED`
- **THEN** the platform rejects access with `WRISTBAND_NOT_BOUND` and does not start timing

#### Scenario: Unknown wristband
- **WHEN** a game backend submits a numeric UID that is absent from SQLite
- **THEN** the platform rejects access with `WRISTBAND_NOT_FOUND`

#### Scenario: Expired active window
- **WHEN** an `ACTIVE` binding's calculated expiry is not after platform-server time
- **THEN** the platform changes the binding and wristband to `EXPIRED`, returns `WRISTBAND_EXPIRED`, and reports zero remaining seconds

### Requirement: Remaining balance is server derived
The platform SHALL derive current wristband remaining time from persisted allocation and start, never from client supplied time, unallocated balance or a former holder's entitlement.

#### Scenario: Client requests current balance
- **WHEN** a caller requests admission or player information for a READY or ACTIVE binding
- **THEN** the original access fields report server-derived allocated remaining time, status and expiry without trusting a client balance

#### Scenario: Account queried after return
- **WHEN** the returned member is queried later or is issued another wristband
- **THEN** remaining balance is unchanged during the paused period and the old wristband is not entitled to use it
