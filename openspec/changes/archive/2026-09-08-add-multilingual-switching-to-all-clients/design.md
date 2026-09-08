## Context

The Electron game desktop already uses Vue I18n plus an Electron-backed locale preference and broadcasts changes to open windows. Its supported set is currently Chinese, English, Russian, Korean, and Japanese. Member Admin and Registration Kiosk are Vue applications without locale selection. The three clients have different layouts and message domains, but they need one canonical locale contract.

## Goals / Non-Goals

**Goals:**

- Use the same 14 BCP 47 locale identifiers in all three clients.
- Provide visible, keyboard-accessible language selectors and restore the last selection after restart.
- Change all open game desktop windows immediately when the locale changes.
- Apply `dir="rtl"` for Arabic and `dir="ltr"` for every other locale.
- Keep locale failure isolated: invalid or damaged preferences fall back to Simplified Chinese.
- Require locale catalogs to expose matching key sets so switching never renders raw keys.
- Keep authored translations separate from runtime fallback so missing translations can be measured instead of hidden.
- Make newly marked source copy exportable as a persistent incremental translation queue.

**Non-Goals:**

- Locale-specific backend error messages or database values.
- Automatic locale detection from Windows or the browser.
- Translating operator-entered member, room, or game names.
- Sharing one runtime preference between physically separate machines.

## Decisions

1. **Canonical locales are fixed and ordered.** Use `zh-CN`, `en-US`, `es-ES`, `pt-PT`, `fr-FR`, `de-DE`, `pl-PL`, `ru-RU`, `vi-VN`, `it-IT`, `cs-CZ`, `ko-KR`, `ro-RO`, and `ar-SA`, displayed with native language names. Japanese is removed from the game selector because it is not in the requested deployment set.
2. **Each installed client persists its own preference.** Electron continues to use its atomic JSON preference store. Browser clients use a namespaced `localStorage` key and validate it before use. This matches the fact that the three clients may run on different machines.
3. **Platform clients share locale metadata, not application copy.** A small platform package exports locale identifiers, labels, direction, normalization, persistence helpers, and the selector model. Member Admin and Registration Kiosk keep separate message catalogs because their UI domains differ.
4. **Selectors fit each interaction model.** Member Admin receives a compact header button with a popover list. Registration Kiosk receives a large touch target and modal/list suitable for unattended touch use. The existing game Language view is retained and its options replaced.
5. **Arabic controls text direction without mirroring the product shell.** Locale application updates `document.documentElement.lang`, keeps the root layout `dir="ltr"`, and records Arabic reading direction separately for text inputs. This preserves the fixed game/kiosk control layout while allowing Arabic text to read correctly.
6. **Catalog parity is tested.** Every client verifies that supported locale catalogs expose the same flattened key set as Simplified Chinese. Missing translations are a test failure rather than a runtime raw-key leak.
7. **Authored and resolved catalogs are separate.** Locale files contain only translations intentionally authored for that locale. Runtime catalogs merge them over the English base, while reports inspect authored catalogs directly so fallback text cannot masquerade as a completed translation.
8. **Extraction is source-driven.** User-visible copy is referenced through stable keys in Vue/JavaScript source. A repository script extracts referenced keys, validates the base catalog, reports per-locale gaps, and emits a deterministic JSON translation queue containing only missing keys. Runtime DOM scraping and online translation are excluded.
9. **Translation policy is tiered.** Customer-critical namespaces are enforced in CI; operator/debug namespaces may use explicit English fallback while the coverage report remains non-zero. This allows incremental delivery without losing visibility of debt.
10. **Flags are locale metadata.** Each of the 14 canonical locale records includes a country code and every selector renders the bundled SVG from `flag-icons`; emoji are not used because Windows does not reliably render country-flag emoji.

## Risks / Trade-offs

- [Large translation surface] The game client has a broad operator and debug UI → structure catalogs by domain and verify key parity automatically.
- [RTL regressions] Existing CSS contains physical left/right properties → set document direction centrally and add focused RTL overrides for navigation, dialogs, and selectors.
- [Old Japanese preference] Existing installs may contain `ja-JP` → normalization falls back to `zh-CN` and the next explicit selection overwrites the legacy value.
- [Separate-machine preferences] Selecting a language on one terminal does not change another machine → this is intentional; each endpoint is independently operated.

- [Machine translation quality] Automatically generated copy may be grammatically valid but operationally wrong; the repository stores generated output for review and uses a shared glossary before it can satisfy critical-namespace checks.

## Migration Plan

1. Ship the expanded game catalog and locale allowlist; legacy unsupported preferences fall back safely.
2. Ship platform shared locale metadata and both browser selectors.
3. Validate catalog parity, persistence, live switching, and Arabic direction in automated tests.
4. Rollback is code-only; existing stored locale strings are ignored by older versions if unsupported.

## Implementation Verification Notes

- Runtime catalog parity guarantees that every supported locale resolves every catalog key without displaying a raw key. Authored-coverage reports separately expose untranslated secondary/operator copy instead of treating inherited English as translated.
- Automated checks covered locale order, persistence, invalid fallback, Arabic direction, state preservation, platform type checking, all client tests, and production builds.
- A final manual pass on the target kiosk and Electron display sizes remains advisable, especially for German/Polish wrapping and Arabic bidirectional layout; no physical target display was attached during this change.
- Both repositories now provide `i18n:report`, `i18n:extract`, `i18n:import`, and `i18n:check`. The extraction queue preserves drafts, import persists completed values into runtime-consumed catalogs, and regeneration removes completed entries from the missing queue.
- Current explicit-coverage reports intentionally remain non-zero: the game source catalog has 671 keys (new locales have 666 untranslated after localized language controls), Member Admin has 16 untranslated catalog keys per newly added locale, and Registration Kiosk has 19. Existing unmarked Vue copy is baselined so it remains visible as migration debt and CI rejects newly introduced unmarked literals.
