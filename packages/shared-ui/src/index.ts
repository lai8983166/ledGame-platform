export const DEFAULT_LOCALE = "zh-CN" as const;

export const PLATFORM_LOCALES = [
  { code: "zh-CN", label: "中文", flag: "🇨🇳", flagCode: "cn", direction: "ltr" },
  { code: "en-US", label: "English", flag: "🇺🇸", flagCode: "us", direction: "ltr" },
  { code: "es-ES", label: "Español", flag: "🇪🇸", flagCode: "es", direction: "ltr" },
  { code: "pt-PT", label: "Português", flag: "🇵🇹", flagCode: "pt", direction: "ltr" },
  { code: "fr-FR", label: "Français", flag: "🇫🇷", flagCode: "fr", direction: "ltr" },
  { code: "de-DE", label: "Deutsch", flag: "🇩🇪", flagCode: "de", direction: "ltr" },
  { code: "pl-PL", label: "Polski", flag: "🇵🇱", flagCode: "pl", direction: "ltr" },
  { code: "ru-RU", label: "Русский", flag: "🇷🇺", flagCode: "ru", direction: "ltr" },
  { code: "vi-VN", label: "Tiếng Việt", flag: "🇻🇳", flagCode: "vn", direction: "ltr" },
  { code: "it-IT", label: "Italiano", flag: "🇮🇹", flagCode: "it", direction: "ltr" },
  { code: "cs-CZ", label: "Čeština", flag: "🇨🇿", flagCode: "cz", direction: "ltr" },
  { code: "ko-KR", label: "한국어", flag: "🇰🇷", flagCode: "kr", direction: "ltr" },
  { code: "ro-RO", label: "Română", flag: "🇷🇴", flagCode: "ro", direction: "ltr" },
  { code: "ar-SA", label: "العربية", flag: "🇸🇦", flagCode: "sa", direction: "rtl" },
] as const;

export type PlatformLocale = typeof PLATFORM_LOCALES[number]["code"];

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface DocumentElementLike {
  lang: string;
  dir: string;
  dataset?: Record<string, string | undefined>;
}

export const PLATFORM_LOCALE_CODES: readonly PlatformLocale[] = Object.freeze(
  PLATFORM_LOCALES.map((locale) => locale.code),
);

export function normalizePlatformLocale(value: unknown): PlatformLocale {
  return typeof value === "string" && PLATFORM_LOCALE_CODES.includes(value as PlatformLocale)
    ? value as PlatformLocale
    : DEFAULT_LOCALE;
}

export function localeDirection(value: unknown): "ltr" | "rtl" {
  return normalizePlatformLocale(value) === "ar-SA" ? "rtl" : "ltr";
}

export function readStoredLocale(storage: StorageLike | null | undefined, key: string): PlatformLocale {
  try {
    return normalizePlatformLocale(storage?.getItem(key));
  } catch {
    return DEFAULT_LOCALE;
  }
}

export function persistLocale(
  storage: StorageLike | null | undefined,
  key: string,
  value: unknown,
): PlatformLocale {
  const locale = normalizePlatformLocale(value);
  try {
    storage?.setItem(key, locale);
  } catch {
    // Storage can be unavailable in hardened kiosk environments; in-memory switching still works.
  }
  return locale;
}

export function applyDocumentLocale(
  element: DocumentElementLike | null | undefined,
  value: unknown,
): PlatformLocale {
  const locale = normalizePlatformLocale(value);
  if (element) {
    element.lang = locale;
    element.dir = "ltr";
    if (element.dataset) element.dataset.languageDirection = localeDirection(locale);
  }
  return locale;
}

export function createCompleteCatalogs<T extends Record<string, string>>(
  base: T,
  overrides: Partial<Record<PlatformLocale, Partial<T>>>,
): Record<PlatformLocale, T> {
  return Object.fromEntries(PLATFORM_LOCALE_CODES.map((locale) => [
    locale,
    { ...base, ...(overrides[locale] || {}) },
  ])) as unknown as Record<PlatformLocale, T>;
}

export type AuthoredCatalogs<T extends Record<string, string>> = Partial<
  Record<PlatformLocale, Partial<T>>
>;

export interface TranslationQueueEntry {
  source: string;
  translation: string;
}

export interface TranslationQueue {
  sourceLocale: "en-US";
  targets: Partial<Record<PlatformLocale, Record<string, TranslationQueueEntry>>>;
}

export type TranslationQueueTargets = TranslationQueue["targets"];

export function applyQueuedTranslations<T extends Record<string, string>>(
  base: T,
  authored: AuthoredCatalogs<T>,
  targets: TranslationQueueTargets | null | undefined,
): AuthoredCatalogs<T> {
  const merged: AuthoredCatalogs<T> = Object.fromEntries(
    Object.entries(authored).map(([locale, catalog]) => [locale, { ...catalog }]),
  );
  for (const locale of PLATFORM_LOCALE_CODES) {
    const entries = targets?.[locale];
    if (!entries) continue;
    const catalog = { ...(merged[locale] || {}) } as Partial<T>;
    for (const [key, entry] of Object.entries(entries)) {
      if (!(key in base) || typeof entry?.translation !== "string" || !entry.translation.trim()) continue;
      catalog[key as keyof T] = entry.translation.trim() as T[keyof T];
    }
    merged[locale] = catalog;
  }
  return merged;
}

export function missingTranslationKeys<T extends Record<string, string>>(
  base: T,
  authored: AuthoredCatalogs<T>,
  locale: PlatformLocale,
): Array<keyof T & string> {
  if (locale === "en-US") return [];
  const catalog = (authored[locale] || {}) as Partial<T>;
  return (Object.keys(base) as Array<keyof T & string>)
    .filter((key) => typeof catalog[key] !== "string" || !catalog[key]?.trim())
    .sort();
}

export function createTranslationQueue<T extends Record<string, string>>(
  base: T,
  authored: AuthoredCatalogs<T>,
  locales: readonly PlatformLocale[] = PLATFORM_LOCALE_CODES,
): TranslationQueue {
  const targets: TranslationQueue["targets"] = {};
  for (const locale of locales) {
    const entries = missingTranslationKeys(base, authored, locale);
    if (!entries.length) continue;
    targets[locale] = Object.fromEntries(entries.map((key) => [
      key,
      { source: base[key], translation: "" },
    ]));
  }
  return { sourceLocale: "en-US", targets };
}

export function interpolationParameters(message: string): string[] {
  return [...message.matchAll(/\{([\w]+)\}/g)].map((match) => match[1]).sort();
}

export function catalogInterpolationErrors<T extends Record<string, string>>(
  base: T,
  authored: AuthoredCatalogs<T>,
): string[] {
  const errors: string[] = [];
  for (const locale of PLATFORM_LOCALE_CODES) {
    const catalog = authored[locale] || {};
    for (const [key, translated] of Object.entries(catalog)) {
      if (typeof translated !== "string" || !(key in base)) continue;
      const expected = interpolationParameters(base[key]);
      const actual = interpolationParameters(translated);
      if (expected.join("\0") !== actual.join("\0")) {
        errors.push(`${locale}:${key} expected {${expected.join(",")}} but found {${actual.join(",")}}`);
      }
    }
  }
  return errors.sort();
}
