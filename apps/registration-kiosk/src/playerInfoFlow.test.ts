import { describe, expect, it, vi } from "vitest";
import type { PlayerInfo } from "@ledgame/platform-api-client";
import { createPlayerInfoFlow } from "./playerInfoFlow";

const playerInfo: PlayerInfo = {
  profile: { id: 1, phone: "13800138000", name: "测试玩家", status: "ACTIVE", createdAt: "2026-08-09T02:00:00Z", createdBy: "kiosk" },
  points: { total: 35, rank: 3 },
  wristbands: [{ uid: "2283055618", status: "ACTIVE", durationMinutes: 60, startedAt: "2026-08-09T02:00:00Z", expiresAt: "2026-08-09T03:00:00Z", remainingSeconds: 1800 }],
  recentPlays: [],
};

describe("Player Info kiosk flow", () => {
  it("queries by phone and exposes persisted profile, points, rank and wristband", async () => {
    const getPlayerInfo = vi.fn().mockResolvedValue(playerInfo);
    const flow = createPlayerInfoFlow({ getPlayerInfo });
    flow.state.phone = "13800138000";

    await flow.query();

    expect(getPlayerInfo).toHaveBeenCalledWith("13800138000");
    expect(flow.state.status).toBe("success");
    expect(flow.state.info).toEqual(playerInfo);
  });

  it("shows not-found and service errors without stale personal data", async () => {
    const getPlayerInfo = vi.fn().mockRejectedValue(new Error("未找到该手机号对应的会员"));
    const flow = createPlayerInfoFlow({ getPlayerInfo });
    flow.state.phone = "13800138000";
    flow.state.info = playerInfo;

    await flow.query();

    expect(flow.state.status).toBe("error");
    expect(flow.state.error).toBe("未找到该手机号对应的会员");
    expect(flow.state.info).toBeNull();
  });

  it("clears all personal state when returning home", () => {
    const flow = createPlayerInfoFlow({ getPlayerInfo: vi.fn() });
    flow.state.phone = "13800138000";
    flow.state.status = "success";
    flow.state.info = playerInfo;

    flow.reset();

    expect(flow.state).toEqual({ phone: "", status: "idle", info: null, error: "" });
  });
});
