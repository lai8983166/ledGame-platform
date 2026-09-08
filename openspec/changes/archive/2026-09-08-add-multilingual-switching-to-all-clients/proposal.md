## Why

The game desktop, member administration client, and self-service registration client must present a consistent language experience for international store deployments. The game desktop currently exposes a smaller, different locale set, while the other two clients have no language switcher at all.

## What Changes

- Standardize all three clients on 14 locales: Simplified Chinese, English, Spanish, Portuguese, French, German, Polish, Russian, Vietnamese, Italian, Czech, Korean, Romanian, and Arabic.
- Replace the game desktop's existing locale list with the standardized set while preserving locale persistence and live window synchronization.
- Add a visible language button and selection list to Member Admin and Registration Kiosk.
- Persist the selected language independently on each installed client and restore it at startup.
- Apply right-to-left document direction when Arabic is selected.
- Add locale catalogs, fallback behavior, and automated coverage for locale parity and switching.
- Add a source-key extraction and coverage-report workflow so new copy is entered once, missing translations remain visible, and only changed keys need translation work.
- Require every locale option to carry a recognizable country flag in all three clients.

## Capabilities

### New Capabilities

- `cross-client-localization`: Defines the supported locale set, switching, persistence, fallback, and Arabic RTL behavior shared by all three clients.

### Modified Capabilities

<!-- No existing main specification defines cross-client language selection. -->

## Impact

- `F:/project/ledGame`: renderer locale catalogs, Electron language settings, language selection UI, preload synchronization, localization reports, and tests.
- `F:/project/ledGame-platform/apps/member-admin`: Vue internationalization setup, shared header language control, persistence, and tests.
- `F:/project/ledGame-platform/apps/registration-kiosk`: Vue internationalization setup, kiosk-safe language control, persistence, and tests.
- The change adds frontend localization data but does not alter backend APIs or stored member/game data.
