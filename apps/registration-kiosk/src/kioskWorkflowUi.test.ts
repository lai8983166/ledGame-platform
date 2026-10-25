import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

describe("registration kiosk workflow UI contract", () => {
  it("offers phone and wristband player-info lookup without using the binding action", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).toContain("playerInfoModeWristband");
    expect(source).toContain("openPlayerInfoScan");
    expect(source).toContain("queryPlayerInfoByWristband");
    expect(source).toContain('data-testid="kiosk-info-mode-wristband"');
    expect(source).not.toContain('data-testid="kiosk-info-scan"');
    expect(source).not.toContain('data-testid="kiosk-info-wristband"');
    expect(source).not.toContain("openInput('infoWristbandUid'");
    expect(source).toContain("data-testid=\"kiosk-info-result\"");
  });

  it("starts with one button and reuses save/check/start in order without a native dialog", async () => {
    const source = await readFile(new URL("./OperatorApp.vue", import.meta.url), "utf8");
    expect(source).not.toContain('data-testid="operator-save"');
    expect(source).not.toContain('data-testid="operator-test"');
    expect(source).toContain('data-testid="operator-error-dialog"');
    expect(source.indexOf('await desktop.saveSettings')).toBeLessThan(source.indexOf('await desktop.testConnection'));
    expect(source.indexOf('await desktop.testConnection')).toBeLessThan(source.indexOf('await desktop.startKiosk'));
    expect(source).toContain('finally { busy.value = false; }');
    expect(source).not.toMatch(/window\.(alert|confirm|prompt)/);
  });

  it("enters activation scan automatically, clears stale success and focuses the reader prompt", async () => {
    const source = await readFile(new URL("./App.vue", import.meta.url), "utf8");
    expect(source).not.toContain('data-testid="kiosk-scan-start"');
    expect(source).toContain('if (target === "swipe") openScanDialog();');
    expect(source).toContain('scanDialog.value?.focus()');
    expect(source).toContain('clearScanTimer();');
    expect(source).toContain('requestRevision === sessionRequestRevision && screen.value === "swipe"');
    expect(source).toContain('if (purpose === "bind" && screen.value === "swipe") goTo(profileScreen.value);');
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
