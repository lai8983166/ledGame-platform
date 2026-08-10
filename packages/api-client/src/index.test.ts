import { afterEach, describe, expect, it, vi } from "vitest";
import { PlatformApiError, createPlatformApiClient } from "./index";

describe("platform api client player info", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns typed Player Info from the local backend", async () => {
    const response = {
      profile: { id: 7, phone: "13800138000", name: "测试玩家", status: "ACTIVE", createdAt: "2026-08-09T02:00:00Z", createdBy: "kiosk" },
      points: { total: 12, rank: 2 },
      wristbands: [{ uid: "2283055618", status: "ACTIVE", durationMinutes: 60, startedAt: "2026-08-09T02:00:00Z", expiresAt: "2026-08-09T03:00:00Z", remainingSeconds: 3300 }],
      recentPlays: [],
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const client = createPlatformApiClient({ baseUrl: "http://127.0.0.1:8090" });
    await expect(client.getPlayerInfo("138 0013 8000")).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/player-info?phone=13800138000",
      expect.objectContaining({ headers: expect.any(Headers) }),
    );
  });

  it("preserves stable server error code and message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "PLAYER_NOT_FOUND", message: "未找到该手机号对应的会员" }),
      { status: 404 },
    )));

    const client = createPlatformApiClient();
    const error = await client.getPlayerInfo("13800138000").catch((reason) => reason);
    expect(error).toBeInstanceOf(PlatformApiError);
    expect(error).toMatchObject({ status: 404, code: "PLAYER_NOT_FOUND", message: "未找到该手机号对应的会员" });
  });
});
