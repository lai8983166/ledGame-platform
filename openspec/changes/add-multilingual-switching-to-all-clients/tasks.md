## 1. Shared locale contract

- [x] 1.1 Define the canonical 14-locale metadata, native labels, normalization, persistence, and direction helpers for platform browser clients.
- [x] 1.2 Add automated tests for locale ordering, invalid fallback, persistence, and Arabic RTL direction.

## 2. Game desktop localization

- [x] 2.1 Replace the Electron and renderer supported-locale allowlists with the canonical set and migrate unsupported saved locales to Simplified Chinese.
- [x] 2.2 Replace the game Language view options and add locale catalogs for Spanish, Portuguese, French, German, Polish, Vietnamese, Italian, Czech, Romanian, and Arabic while removing Japanese.
- [x] 2.3 Apply document language/direction in every game renderer window and preserve live Electron locale broadcasts.
- [x] 2.4 Update game localization tests for the 14-locale set, catalog parity, persistence, window synchronization, and RTL behavior.

## 3. Member Admin localization

- [x] 3.1 Add reactive localization initialization and structurally complete 14-locale Member Admin catalogs.
- [x] 3.2 Add a compact header language button and accessible selection popover.
- [x] 3.3 Persist and restore the Member Admin locale and apply document language/direction.
- [x] 3.4 Add tests for catalog parity, switching, persistence, invalid fallback, and RTL direction.

## 4. Registration Kiosk localization

- [x] 4.1 Add reactive localization initialization and structurally complete 14-locale Registration Kiosk catalogs.
- [x] 4.2 Add a touch-friendly language button and selection list without disrupting phone, registration, wristband, and player-info flows.
- [x] 4.3 Persist and restore the Registration Kiosk locale, reset transient language UI state safely, and apply document language/direction.
- [x] 4.4 Add tests for catalog parity, switching, persistence, invalid fallback, RTL direction, and core-flow state preservation.

## 5. Verification

- [x] 5.1 Run platform typechecks, unit tests, and production builds.
- [x] 5.2 Run the game desktop unit test suite and production build.
- [x] 5.3 Validate the OpenSpec change strictly and document any untranslated fallback or manual visual checks that remain.

## 6. Maintainable translation workflow

- [x] 6.1 Extend canonical locale metadata and tests so every language has a country flag rendered by all three selectors.
- [x] 6.2 Add shared authored-vs-resolved catalog coverage helpers with interpolation validation and deterministic missing-key reports.
- [x] 6.3 Add platform source-key extraction, coverage reporting, strict critical-namespace checking, and persistent incremental translation queue commands.
- [x] 6.4 Adapt Member Admin and Registration Kiosk catalogs to expose authored translations separately from English runtime fallback.
- [x] 6.5 Add equivalent game-desktop coverage/report/queue commands without breaking Vue I18n or Electron live switching.
- [x] 6.6 Add tests proving fallback does not hide missing translations and completed translations are excluded from incremental queues.
- [x] 6.7 Run all platform/game tests, typechecks, builds, localization reports, and strict OpenSpec validation.

## 7. Windows flag and Arabic layout correction

- [x] 7.1 Replace emoji flags with bundled SVG-backed country flags in all three selectors.
- [x] 7.2 Keep the three application shells left-to-right for Arabic while retaining an explicit Arabic text-direction marker.
- [x] 7.3 Update regression tests and rerun localization checks, tests, typechecks, builds, and strict OpenSpec validation.
