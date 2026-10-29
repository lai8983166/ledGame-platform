# Spec Delta

## ADDED Requirements

### Requirement: Player information distinguishes available and ranking points
Player Info SHALL expose remaining redeemable points separately from cumulative ranking points. The existing total and rank fields SHALL retain their settled-game meaning. The kiosk SHALL show both values without providing a deduction operation.

#### Scenario: Query after redemption
- **WHEN** a member has earned 10000 points and redeemed 5000
- **THEN** phone and wristband queries return remaining 5000, ranking and legacy total 10000, and the unchanged rank

#### Scenario: Compatibility with an older response
- **WHEN** the kiosk receives only the original total and rank fields
- **THEN** it can still render both point displays using total as the fallback without failing the query
