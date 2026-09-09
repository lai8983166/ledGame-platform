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

  it("queries by wristband UID without changing the read-only flow", async () => {
    const getPlayerInfo = vi.fn().mockResolvedValue(playerInfo);
    const flow = createPlayerInfoFlow({ getPlayerInfo });
    flow.state.mode = "wristband";
    flow.state.wristbandUid = " 2283055618 ";

    await flow.query();

    expect(getPlayerInfo).toHaveBeenCalledWith({ wristbandUid: "2283055618" });
    expect(flow.state.status).toBe("success");
    expect(flow.state.info).toEqual(playerInfo);
  });

  it("rejects an invalid wristband UID before calling the backend", async () => {
    const getPlayerInfo = vi.fn();
    const flow = createPlayerInfoFlow({ getPlayerInfo });
    flow.state.mode = "wristband";
    flow.state.wristbandUid = "not-a-uid";

    await flow.query();

    expect(flow.state.status).toBe("error");
    expect(flow.state.error).toContain("wristband UID");
    expect(getPlayerInfo).not.toHaveBeenCalled();
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

    expect(flow.state).toEqual({ mode: "phone", phone: "", wristbandUid: "", status: "idle", info: null, error: "" });
  });

  it("ignores a response that finishes after the flow was reset", async () => {
    let resolveQuery: ((value: PlayerInfo) => void) | undefined;
    const getPlayerInfo = vi.fn().mockImplementation(() => new Promise<PlayerInfo>((resolve) => { resolveQuery = resolve; }));
    const flow = createPlayerInfoFlow({ getPlayerInfo });
    flow.state.phone = "13800138000";
    const pending = flow.query();
    flow.reset();
    resolveQuery?.(playerInfo);
    await pending;
    expect(flow.state).toEqual({ mode: "phone", phone: "", wristbandUid: "", status: "idle", info: null, error: "" });
  });
});
