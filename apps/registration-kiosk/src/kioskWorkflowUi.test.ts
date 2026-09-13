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

  it("offers an optional recoverable camera capture flow without removing the avatar library", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain('data-testid="kiosk-avatar-camera-open"');
    expect(source).toContain('data-testid="kiosk-camera-video"');
    expect(source).toContain('data-testid="kiosk-camera-capture"');
    expect(source).toContain('data-testid="kiosk-camera-retake"');
    expect(source).toContain('data-testid="kiosk-camera-cancel"');
    expect(source).toContain("data-testid=\"kiosk-avatar-library-open\"");
    expect(source).toContain("navigator.mediaDevices.getUserMedia");
    expect(source).toContain('addEventListener("ended"');
  });

  it("keeps the offline message focused on the member-admin network", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("text('offlineMessage')");
    expect(source).not.toContain("{{ desktopConnectionMessage }}");
  });
});
