import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const platformRoot = resolve(process.cwd());
const gameRoot = resolve(platformRoot, "..", "ledGame");
const source = (root: string, path: string) => readFileSync(resolve(root, path), "utf8");

describe("acceptance interaction contracts", () => {
  it("exposes stable Member Admin business selectors", () => {
    const app = source(platformRoot, "apps/member-admin/src/App.vue");
    const wristbands = source(platformRoot, "apps/member-admin/src/views/WristbandsView.vue");
    const rooms = source(platformRoot, "apps/member-admin/src/views/RoomsView.vue");
    const members = source(platformRoot, "apps/member-admin/src/views/MembersView.vue");
    const dashboard = source(platformRoot, "apps/member-admin/src/views/DashboardView.vue");

    expect(app).toContain("admin-nav-${item.id}");
    for (const selector of ["admin-charge-start", "admin-charge-dialog", "admin-charge-scanned-uid", "admin-charge-submit", "admin-wristband-${wristband.uid}", "admin-wristband-status"]) {
      expect(wristbands).toContain(selector);
    }
    expect(rooms).toContain("admin-room-${room.ip}");
    expect(members).toContain("admin-member-${member.id}");
    expect(members).toContain("admin-member-delete-confirm");
    for (const selector of ["admin-dashboard-refresh", "admin-dashboard-total-members", "admin-dashboard-new-members-today", "admin-dashboard-wristbands-charged-today", "admin-dashboard-revenue-today"]) {
      expect(dashboard).toContain(selector);
    }
  });

  it("keeps kiosk binding behind an explicit keyboard-wedge scan session", () => {
    const kiosk = source(platformRoot, "apps/registration-kiosk/src/App.vue");

    for (const selector of ["kiosk-member-phone", "kiosk-scan-start", "kiosk-scan-dialog", "kiosk-scan-cancel", "kiosk-bind-success", "kiosk-info-result"]) {
      expect(kiosk).toContain(selector);
    }
    expect(kiosk).not.toContain("kiosk-wristband-uid");
    expect(kiosk).toContain("consumeWristbandScanKey(wristbandScan, event.key)");
  });

  it("exposes Electron lifecycle state without bypassing the production reader", () => {
    const touch = source(gameRoot, "src/views/LedGameTouchView.vue");
    const debug = source(gameRoot, "src/views/DemoView.vue");
    const reader = source(gameRoot, "electron/wristband-reader.cjs");

    for (const selector of ["game-touch", "game-wristband-prompt", "game-player-access", "game-start", "game-queue-submit", "game-return-idle"]) {
      expect(touch).toContain(selector);
    }
    expect(debug).toContain("game-debug-end");
    expect(debug).toContain("game-debug-complete-natural");
    expect(reader).toMatch(/key === 'Enter'/);
    expect(reader).toMatch(/\^\\d\+\$/);
  });

  it("contains a production-shaped bidirectional floor acceptance path", () => {
    const harness = source(platformRoot, "acceptance/support/storeHarness.ts");
    const productionSpec = source(platformRoot, "acceptance/specs/store-production-floor.spec.ts");

    expect(harness).toContain("BidirectionalFloorDevice");
    expect(harness).toContain('options.runtimeMode === "PRODUCTION"');
    expect(harness).toContain("sendFloorTap");
    expect(productionSpec).toContain("PRODUCTION 软件形态");
    expect(productionSpec).toContain("completeCurrentGameThroughFloor");
  });
});
