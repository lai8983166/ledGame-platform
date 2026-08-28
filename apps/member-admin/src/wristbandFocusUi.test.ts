import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const wristbands = readFileSync(new URL("./views/WristbandsView.vue", import.meta.url), "utf8");
const modal = readFileSync(new URL("./components/BaseModal.vue", import.meta.url), "utf8");
const drawer = readFileSync(new URL("./components/SideDrawer.vue", import.meta.url), "utf8");

describe("member admin dialog focus continuity", () => {
  it("uses an in-app wristband confirmation instead of native confirm", () => {
    expect(wristbands).not.toContain("window.confirm");
    expect(wristbands).toContain('data-testid="admin-wristband-action-confirm-dialog"');
    expect(wristbands).toContain("confirmWristbandAction");
  });

  it("restores the opener from both shared overlay primitives", () => {
    for (const source of [modal, drawer]) {
      expect(source).toContain("captureFocusReturnTarget");
      expect(source).toContain("restoreFocusReturnTarget");
    }
  });
});
