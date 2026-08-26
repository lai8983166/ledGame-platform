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

  it("starts scanning with a button and no customer-editable UID field", () => {
    expect(kiosk).toContain('data-testid="kiosk-scan-start"');
    expect(kiosk).toContain('data-testid="kiosk-scan-dialog"');
    expect(kiosk).toContain('data-testid="kiosk-scan-cancel"');
    expect(kiosk).not.toContain('data-testid="kiosk-wristband-uid"');
  });
});
