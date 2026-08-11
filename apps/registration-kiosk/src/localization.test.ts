import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { PLATFORM_LOCALE_CODES } from "@ledgame/platform-shared-ui";
import { registrationKioskCatalogs } from "./localization";

describe("registration kiosk localization", () => {
  it("provides a structurally complete catalog for every supported locale", () => {
    const reference = Object.keys(registrationKioskCatalogs["en-US"]).sort();
    expect(Object.keys(registrationKioskCatalogs)).toEqual(PLATFORM_LOCALE_CODES);
    for (const catalog of Object.values(registrationKioskCatalogs)) {
      expect(Object.keys(catalog).sort()).toEqual(reference);
      expect(Object.values(catalog).every(Boolean)).toBe(true);
    }
  });

  it("offers all locales and changes language without clearing registration state", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("v-for=\"item in PLATFORM_LOCALES\"");
    expect(source).toContain('localeFlagUrls[item.flagCode]');
    expect(source).toContain('<img :src=');
    expect(source).toContain("persistLocale(window.localStorage");
    expect(source).toContain("applyDocumentLocale(document.documentElement");
    const selectionBody = source.slice(source.indexOf("const selectLocale"), source.indexOf("onMounted"));
    expect(selectionBody).not.toContain("resetSession");
    expect(selectionBody).not.toContain("screen.value =");
  });
});
