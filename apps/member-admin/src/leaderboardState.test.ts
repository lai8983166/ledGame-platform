import { describe, expect, it, vi } from "vitest";
import type { LeaderboardResponse } from "@ledgame/platform-api-client";
import { createLeaderboardState, loadLeaderboard } from "./leaderboardState";

const response = (period: "day" | "month" | "year", points: number): LeaderboardResponse => ({
  period,
  periodStart: "2026-08-01T00:00:00+08:00",
  periodEnd: "2026-09-01T00:00:00+08:00",
  generatedAt: "2026-08-26T12:00:00+08:00",
  entries: [{ rank: 1, memberId: 7, memberName: "真实玩家", avatarId: "nova", points, completedGames: 2 }],
});
describe("leaderboard request state", () => {
  it("loads real data and records errors without demo fallback", async () => {
    const state = createLeaderboardState();
    await loadLeaderboard(state, "day", vi.fn().mockResolvedValue(response("day", 42)));
    expect(state.status).toBe("success");
    expect(state.data?.entries[0].points).toBe(42);

    await loadLeaderboard(state, "day", vi.fn().mockRejectedValue(new Error("本机服务不可用")));
    expect(state.status).toBe("error");
    expect(state.error).toContain("本机服务不可用");
    expect(state.data).toBeNull();
  });

  it("ignores a late response after the period changes", async () => {
    const state = createLeaderboardState();
    let resolveDay!: (value: LeaderboardResponse) => void;
    const day = new Promise<LeaderboardResponse>((resolve) => (resolveDay = resolve));
    const dayLoad = loadLeaderboard(state, "day", () => day);
    await loadLeaderboard(state, "month", async () => response("month", 99));
    resolveDay(response("day", 1));
    await dayLoad;
    expect(state.period).toBe("month");
    expect(state.data?.period).toBe("month");
    expect(state.data?.entries[0].points).toBe(99);
  });
});
