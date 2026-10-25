# Spec Delta

## ADDED Requirements

### Requirement: Player information includes ranking-based level
The platform SHALL return the member's current level name derived from ranking points and current level ranges. Kiosk information remains read-only and displays a localized unrated label when no range matches or an older response omits the field.

#### Scenario: Redemption preserves level
- **WHEN** a member redeems points and queries by phone or wristband
- **THEN** remaining points decrease but the level continues to match ranking points without changing any existing point fields
