## ADDED Requirements

### Requirement: All clients expose the canonical locale set
The game desktop, Member Admin, and Registration Kiosk SHALL offer exactly Simplified Chinese, English, Spanish, Portuguese, French, German, Polish, Russian, Vietnamese, Italian, Czech, Korean, Romanian, and Arabic in the same order.

#### Scenario: Operator opens a language selector
- **WHEN** an operator opens the language selector in any client
- **THEN** the client displays all 14 canonical languages using recognizable native-language labels and a country flag for each option

#### Scenario: Legacy game locale is unsupported
- **WHEN** a stored game locale is not in the canonical set, including the previously supported Japanese locale
- **THEN** the game client falls back to Simplified Chinese without preventing startup

### Requirement: Language selection is persistent and immediate
Each client SHALL apply a selected supported locale immediately, persist it on that installed client, and restore it on the next startup.

#### Scenario: Browser client language is changed
- **WHEN** an operator selects a supported locale in Member Admin or Registration Kiosk
- **THEN** visible interface text changes without a page reload and the same locale is restored after reopening the client

#### Scenario: Game desktop language is changed
- **WHEN** an operator selects a supported locale in the game desktop
- **THEN** every open game desktop window receives and applies the locale change

### Requirement: Invalid locale preferences fail safely
Each client SHALL validate persisted and requested locale identifiers and SHALL use Simplified Chinese when the value is missing, damaged, or unsupported.

#### Scenario: Persisted locale is invalid
- **WHEN** a client loads an invalid locale preference
- **THEN** it starts in Simplified Chinese and still allows the operator to select another supported locale

### Requirement: Arabic text uses right-to-left presentation without mirroring the application
Each client SHALL set the document language from the active locale, preserve the established left-to-right application layout, and expose right-to-left reading direction for Arabic text entry.

#### Scenario: Arabic is selected
- **WHEN** the active locale becomes `ar-SA`
- **THEN** the document uses `lang="ar-SA"`, keeps the application layout left-to-right, and Arabic text remains readable

#### Scenario: A non-Arabic locale is selected after Arabic
- **WHEN** the active locale changes from Arabic to another supported locale
- **THEN** the document layout remains left-to-right and the Arabic text-direction marker is cleared

### Requirement: Locale catalogs remain structurally complete
Every client SHALL provide the same message-key structure for every supported locale and SHALL render fallback content rather than raw translation keys.

#### Scenario: Catalog parity test runs
- **WHEN** automated localization tests flatten every locale catalog
- **THEN** each catalog has the same key set and interpolation parameters as the Simplified Chinese reference catalog

### Requirement: Missing translations remain observable
Each client SHALL distinguish explicitly authored translations from values supplied by runtime fallback, and repository tooling SHALL report missing authored translations by locale.

#### Scenario: A new source key has no target translation
- **WHEN** localization reporting runs after a base-language key is added
- **THEN** every locale without an authored value lists that key as missing even though the application can render the English fallback

### Requirement: Translation work is incremental and persistent
Repository tooling SHALL extract stable localization keys from source, validate them against the base catalog, and emit a deterministic persistent queue containing only missing translations.

#### Scenario: A developer adds marked interface copy
- **WHEN** the extraction command runs
- **THEN** the base key is validated and missing locale entries are written to the translation queue without rewriting completed translations

#### Scenario: Critical customer copy is untranslated
- **WHEN** the strict localization check runs
- **THEN** it fails for missing translations in configured customer-critical namespaces while non-critical fallback remains reportable
