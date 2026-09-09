import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

describe("registration kiosk workflow UI contract", () => {
  it("offers phone and wristband player-info lookup without using the binding action", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("playerInfoModeWristband");
    expect(source).toContain("openPlayerInfoScan");
    expect(source).toContain("queryPlayerInfoByWristband");
    expect(source).toContain("data-testid=\"kiosk-info-scan\"");
    expect(source).toContain("data-testid=\"kiosk-info-result\"");
  });

  it("exposes persisted timeout settings and a visible countdown", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("readFlowTimeouts(window.localStorage)");
    expect(source).toContain("writeFlowTimeouts(window.localStorage");
    expect(source).toContain("data-testid=\"kiosk-settings-open\"");
    expect(source).toContain("data-testid=\"kiosk-settings-save\"");
    expect(source).toContain("data-testid=\"kiosk-flow-countdown\"");
  });

  it("keeps the offline message focused on the member-admin network", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("text('offlineMessage')");
    expect(source).not.toContain("{{ desktopConnectionMessage }}");
  });
});
