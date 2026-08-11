# Localization workflow

User-visible copy is referenced from Vue source with stable keys such as `text("navMembers")`. English is the source catalog. Authored locale values remain separate from runtime English fallback, so missing translations stay measurable.

Commands:

- `pnpm i18n:report` prints authored coverage and existing unmarked Vue copy.
- `pnpm i18n:extract` writes only missing entries to `translation-queue.json` and preserves draft translations already entered there.
- Fill the `translation` fields in `translation-queue.json`, then run `pnpm i18n:import`. Completed entries move to `translations.json`, are consumed by both browser clients, and disappear from the next queue.
- `pnpm i18n:check` validates flags, referenced keys, interpolation variables, critical customer copy, and prevents newly introduced unmarked Vue literals.

The checked-in unmarked baseline records legacy copy that still needs migration. Do not refresh it for new features; new user-facing copy must receive a localization key.
