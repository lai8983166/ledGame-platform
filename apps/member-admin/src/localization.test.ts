import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { PLATFORM_LOCALE_CODES } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs } from "./localization";

describe("member admin localization", () => {
  it("provides a structurally complete catalog for every supported locale", () => {
    const reference = Object.keys(memberAdminCatalogs["en-US"]).sort();
    expect(Object.keys(memberAdminCatalogs)).toEqual(PLATFORM_LOCALE_CODES);
    for (const catalog of Object.values(memberAdminCatalogs)) {
      expect(Object.keys(catalog).sort()).toEqual(reference);
      expect(Object.values(catalog).every(Boolean)).toBe(true);
    }
  });

  it("renders a persisted language selector without resetting the active page", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("v-for=\"item in PLATFORM_LOCALES\"");
    expect(source).toContain('localeFlagUrls[item.flagCode]');
    expect(source).toContain('<img :src=');
    expect(source).toContain("persistLocale(window.localStorage");
    expect(source).toContain("applyDocumentLocale(document.documentElement");
    const selectionBody = source.slice(source.indexOf("const selectLocale"), source.indexOf("const navigate"));
    expect(selectionBody).not.toContain("activePage.value =");
  });
});
