import { describe, expect, it } from "vitest";
import {
  catalogInterpolationErrors,
  createTranslationQueue,
  DEFAULT_LOCALE,
  PLATFORM_LOCALES,
  PLATFORM_LOCALE_CODES,
  applyDocumentLocale,
  localeDirection,
  missingTranslationKeys,
  normalizePlatformLocale,
  persistLocale,
  readStoredLocale,
} from "./index";

describe("platform locale contract", () => {
  it("keeps the canonical locale order", () => {
    expect(PLATFORM_LOCALE_CODES).toEqual([
      "zh-CN", "en-US", "es-ES", "pt-PT", "fr-FR", "de-DE", "pl-PL",
      "ru-RU", "vi-VN", "it-IT", "cs-CZ", "ko-KR", "ro-RO", "ar-SA",
    ]);
  });

  it("gives every locale a country flag and native label", () => {
    expect(PLATFORM_LOCALES.every((locale) => locale.flagCode && locale.label)).toBe(true);
    expect(new Set(PLATFORM_LOCALES.map((locale) => locale.flagCode)).size).toBe(14);
  });

  it("falls back safely and persists supported values", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => void values.set(key, value),
    };
    expect(normalizePlatformLocale("ja-JP")).toBe(DEFAULT_LOCALE);
    expect(readStoredLocale(storage, "locale")).toBe(DEFAULT_LOCALE);
    expect(persistLocale(storage, "locale", "fr-FR")).toBe("fr-FR");
    expect(readStoredLocale(storage, "locale")).toBe("fr-FR");
  });

  it("uses RTL only for Arabic", () => {
    const element = { lang: "", dir: "", dataset: {} as Record<string, string> };
    applyDocumentLocale(element, "ar-SA");
    expect(element).toEqual({ lang: "ar-SA", dir: "ltr", dataset: { languageDirection: "rtl" } });
    expect(localeDirection("de-DE")).toBe("ltr");
    applyDocumentLocale(element, "de-DE");
    expect(element).toEqual({ lang: "de-DE", dir: "ltr", dataset: { languageDirection: "ltr" } });
  });
});

describe("translation coverage", () => {
  const base = { greeting: "Hello {name}", save: "Save" };
  const authored = {
    "zh-CN": { greeting: "你好 {name}" },
    "es-ES": { greeting: "Hola {nombre}" },
  };

  it("reports authored gaps even when runtime fallback can fill them", () => {
    expect(missingTranslationKeys(base, authored, "zh-CN")).toEqual(["save"]);
    expect(missingTranslationKeys(base, authored, "en-US")).toEqual([]);
    const queue = createTranslationQueue(base, authored, ["zh-CN"]);
    expect(queue.targets["zh-CN"]).toEqual({ save: { source: "Save", translation: "" } });
  });

  it("rejects translated interpolation parameters that changed names", () => {
    expect(catalogInterpolationErrors(base, authored)).toEqual([
      "es-ES:greeting expected {name} but found {nombre}",
    ]);
  });
});
