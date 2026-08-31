import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync(new URL("./views/LeaderboardView.vue", import.meta.url), "utf8");
const kiosk = readFileSync(new URL("../../registration-kiosk/src/App.vue", import.meta.url), "utf8");

describe("real leaderboard and explicit kiosk scan UI", () => {
  it("does not import demo rankings or render fabricated trend values", () => {
    expect(source).not.toContain('import { rankings } from "../data"');
    expect(source).not.toContain("entry.trend");
    expect(source).toContain('data-testid="admin-leaderboard-refresh"');
    expect(source).toContain('data-testid="admin-leaderboard-state"');
  });

  it("removes preview copy while keeping the secondary-screen entry disabled in code", () => {
    expect(source).not.toContain("每一次跃动，都值得被看见");
    expect(source).not.toContain("真实积分数据 · 当前为本机 UI 预览，未连接电视设备");
    expect(source).not.toContain("leaderboardSourceNote");
    expect(source).toContain("const secondaryScreenPreviewEnabled = false");
    expect(source).toContain('v-if="secondaryScreenPreviewEnabled"');
  });

  it("starts scanning with a button and no customer-editable UID field", () => {
    expect(kiosk).toContain('data-testid="kiosk-scan-start"');
    expect(kiosk).toContain('data-testid="kiosk-scan-dialog"');
    expect(kiosk).toContain('data-testid="kiosk-scan-cancel"');
    expect(kiosk).not.toContain('data-testid="kiosk-wristband-uid"');
  });
});
