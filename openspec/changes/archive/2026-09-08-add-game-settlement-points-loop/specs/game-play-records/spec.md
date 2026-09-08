## MODIFIED Requirements

### Requirement: Game result settlement is idempotent
The platform SHALL persist the terminal result, raw score, platform-derived awarded points, scoring-policy version, result payload, and end time at most once per play.

#### Scenario: Settle a natural game completion
- **WHEN** the game backend submits a natural success or natural failure result for a running play
- **THEN** the platform marks the play `COMPLETED`, calculates awarded points from the persisted game identity and submitted raw score, and stores the immutable settlement

#### Scenario: Repeat the same result
- **WHEN** delivery retry submits a result for an already settled play
- **THEN** the platform returns the stored settlement without recalculating or adding points a second time

#### Scenario: Submit a conflicting result after settlement
- **WHEN** a caller submits a different terminal payload for an already settled play
- **THEN** the platform preserves and returns the first committed settlement and records no additional points

#### Scenario: Settle a stopped game
- **WHEN** the game backend reports a manual stop, startup abort, or runtime failure
- **THEN** the platform marks the play `ABORTED`, awards zero points, and preserves the supplied termination reason and diagnostic result data

## ADDED Requirements

### Requirement: Awarded points are platform authoritative
The platform SHALL ignore any client-supplied awarded-points value and SHALL calculate points through a versioned server-side scoring policy.

#### Scenario: Apply the initial raw-score policy
- **WHEN** a natural settlement supplies a raw score
- **THEN** policy `raw-score-v1` awards `max(0, rawScore)` points and persists both the decision and policy version

#### Scenario: Natural settlement has no score
- **WHEN** a naturally completed game cannot provide a numeric raw score
- **THEN** the platform awards zero points and still stores the completed result and scoring-policy version

#### Scenario: Legacy caller supplies awarded points
- **WHEN** a transition-period client submits `pointsAwarded` that differs from the platform calculation
- **THEN** the platform ignores that field and returns the server-derived value

