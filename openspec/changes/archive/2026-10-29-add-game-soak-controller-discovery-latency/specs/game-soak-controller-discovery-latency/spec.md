## Purpose

This capability gives real-hardware game soak acceptance a compact, auditable measure of controller discovery responsiveness without retaining per-request timestamps or unbounded packet evidence.

## ADDED Requirements

### Requirement: Periodic discovery probe

The real-hardware soak run SHALL trigger one ELC-408 controller discovery transaction every 10 seconds while the run is active. A transaction SHALL use the SDK's existing discovery protocol and matching reply validation, including the protocol's repeated search packets.

#### Scenario: Real hardware run probes periodically

- **WHEN** a soak run is configured for real hardware and remains active for at least 20 seconds
- **THEN** the game backend SHALL receive at least one discovery probe at approximately each 10-second sampling interval and SHALL record the probe attempt in cumulative metrics

#### Scenario: Simulated run does not pretend to verify hardware

- **WHEN** a soak run is configured for simulated hardware
- **THEN** it SHALL not send controller discovery packets and its discovery-latency result SHALL be reported as 未验证 rather than 通过

### Requirement: Aggregate response latency

For each unique, protocol-valid controller reply matched to a probe, the system SHALL calculate elapsed monotonic time from the first search packet send to that reply, add it to a cumulative latency total, and increment a sample count. It MUST NOT retain individual send or receive timestamps for this metric.

#### Scenario: Matching reply produces a sample

- **WHEN** a controller returns a valid 0x68 reply matching the active 0x67 discovery request
- **THEN** the metrics SHALL increase the response sample count and latency total, and the final report SHALL expose the arithmetic average as total divided by sample count in milliseconds

#### Scenario: Duplicate replies do not inflate the sample

- **WHEN** the same controller replies more than once to the repeated packets within one discovery transaction
- **THEN** only the first valid reply for that controller in that transaction SHALL contribute a latency sample

#### Scenario: No reply is received

- **WHEN** the bounded discovery wait expires without a valid matching reply
- **THEN** the probe attempt SHALL complete with a timeout counter, the average SHALL exclude that attempt, and the run SHALL continue to the next interval

### Requirement: Acceptance evidence and reporting

The soak summary SHALL include discovery probe attempts, valid response samples, timeouts, cumulative latency, and average response latency. The report SHALL clearly distinguish a real measured average from 未验证 when no hardware samples exist, while preserving existing communication fault judgments.

#### Scenario: Report includes measured average

- **WHEN** at least one valid response sample was collected during a real-hardware run
- **THEN** the acceptance report SHALL show the sample count and average response latency in milliseconds without listing individual timestamps

#### Scenario: Report has no valid samples

- **WHEN** a real-hardware run collected no valid response samples
- **THEN** the report SHALL show 未验证 for the average and include the attempt/timeout counts so the missing evidence is explainable

#### Scenario: Existing communication faults remain visible

- **WHEN** discovery probes or normal SDK metrics report send, receive, or parse faults
- **THEN** the communication result SHALL remain 失败 according to the existing rules and SHALL include the discovery counters as supporting evidence
