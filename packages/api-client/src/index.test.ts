import { afterEach, describe, expect, it, vi } from "vitest";
import { PlatformApiError, createPlatformApiClient } from "./index";

afterEach(() => vi.unstubAllGlobals());

describe("platform api client transport", () => {
  it("uses the injected desktop transport without calling browser fetch", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const transport = vi.fn().mockResolvedValue({
      status: 200,
      body: JSON.stringify({ ok: true }),
    });

    const client = createPlatformApiClient({ transport });
    await expect(client.request("/api/health")).resolves.toEqual({ ok: true });
    expect(transport).toHaveBeenCalledWith(expect.objectContaining({
      path: "/api/health",
      method: "GET",
    }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("adds the current in-memory operator id to authenticated reads and mutations", async () => {
    const transport = vi.fn().mockResolvedValue({ status: 200, body: JSON.stringify({ ok: true }) });
    const client = createPlatformApiClient({ transport, operatorIdProvider: () => 42 });

    await client.request("/api/wristbands/charge", { method: "POST", body: "{}" });
    expect(transport).toHaveBeenCalledWith(expect.objectContaining({
      headers: expect.objectContaining({ "x-operator-id": "42" }),
    }));

    await client.request("/api/rooms");
    expect(transport.mock.calls[1][0].headers).toHaveProperty("x-operator-id", "42");
  });

  it("does not add an operator header while logged out", async () => {
    const transport = vi.fn().mockResolvedValue({ status: 200, body: "{}" });
    const client = createPlatformApiClient({ transport, operatorIdProvider: () => null });
    await client.request("/api/members", { method: "POST", body: "{}" });
    expect(transport.mock.calls[0][0].headers).not.toHaveProperty("x-operator-id");
  });
});

describe("platform operator account api", () => {
  it("logs in and manages accounts without exposing password fields", async () => {
    const responses = [
      { id: 1, username: "admin", displayName: "出厂管理员", accountType: "FACTORY_ADMIN" },
      [{ id: 1, username: "admin", displayName: "出厂管理员", accountType: "FACTORY_ADMIN", enabled: true }],
      { id: 2, username: "counter", displayName: "前台", accountType: "OPERATOR", enabled: true },
    ];
    const transport = vi.fn()
      .mockResolvedValueOnce({ status: 200, body: JSON.stringify(responses[0]) })
      .mockResolvedValueOnce({ status: 200, body: JSON.stringify(responses[1]) })
      .mockResolvedValueOnce({ status: 200, body: JSON.stringify(responses[2]) });
    const client = createPlatformApiClient({ transport });

    await expect(client.loginOperator("admin", "888888")).resolves.toEqual(responses[0]);
    await expect(client.listOperatorAccounts()).resolves.toEqual(responses[1]);
    await expect(client.createOperatorAccount({
      username: "counter", displayName: "前台", password: "123456",
    })).resolves.toEqual(responses[2]);

    expect(transport.mock.calls.map(([request]) => [request.path, request.method, request.body])).toEqual([
      ["/api/operator-auth/login", "POST", JSON.stringify({ username: "admin", password: "888888" })],
      ["/api/operator-accounts", "GET", undefined],
      ["/api/operator-accounts", "POST", JSON.stringify({ username: "counter", displayName: "前台", password: "123456" })],
    ]);
  });

  it("records a successful persistent system setting change", async () => {
    const transport = vi.fn().mockResolvedValue({ status: 204, body: "" });
    const client = createPlatformApiClient({ transport, operatorIdProvider: () => 7 });

    await expect(client.recordSystemSettingsChange()).resolves.toBeUndefined();
    expect(transport).toHaveBeenCalledWith(expect.objectContaining({
      path: "/api/operator-actions/system-settings",
      method: "POST",
      headers: expect.objectContaining({ "x-operator-id": "7" }),
    }));
  });
});

describe("database backup api", () => {
  it("loads typed backup status and factory candidate metadata without exposing paths", async () => {
    const status = {
      state: "READY_DEGRADED", phase: "COMPLETE", protectedData: false,
      targetVolume: null, lastSuccessfulBackupAt: null, sourceRevision: 8,
      backupRevision: 7, errorCode: "NO_CROSS_DISK_TARGET", message: "未找到另一块物理硬盘",
    };
    const candidate = {
      candidateId: "opaque-id", sourceType: "LATEST", revision: 7,
      lastBusinessModifiedAt: "2026-09-02T00:00:00Z", generatedAt: "2026-09-02T00:00:01Z",
      fileSize: 1024, environment: "PRODUCTION", factoryAdminUsername: "admin", memberCount: 3, valid: true,
    };
    const transport = vi.fn()
      .mockResolvedValueOnce({ status: 200, body: JSON.stringify(status) })
      .mockResolvedValueOnce({ status: 200, body: JSON.stringify([candidate]) });
    const client = createPlatformApiClient({ transport, operatorIdProvider: () => 1 });

    await expect(client.getDatabaseBackupStatus()).resolves.toEqual(status);
    await expect(client.listDatabaseBackupCandidates()).resolves.toEqual([candidate]);
    expect(transport.mock.calls.every(([request]) => request.headers["x-operator-id"] === "1")).toBe(true);
    expect(JSON.stringify(candidate)).not.toContain("Path");
    expect(JSON.stringify(candidate)).not.toContain("serial");
  });

  it("confirms keeping the current database through the authenticated maintenance endpoint", async () => {
    const status = { state: "READY_PROTECTED", phase: "COMPLETE", protectedData: true };
    const transport = vi.fn().mockResolvedValue({ status: 200, body: JSON.stringify(status) });
    const client = createPlatformApiClient({ transport, operatorIdProvider: () => 1 });

    await expect(client.keepCurrentDatabase()).resolves.toEqual(status);
    expect(transport).toHaveBeenCalledWith(expect.objectContaining({
      path: "/api/database-backup/conflicts/use-current",
      method: "POST",
      headers: expect.objectContaining({ "x-operator-id": "1" }),
    }));
  });
});

describe("platform api client player info", () => {
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

  it("queries a typed real leaderboard period", async () => {
    const response = {
      period: "month",
      periodStart: "2026-08-01T00:00:00+08:00",
      periodEnd: "2026-09-01T00:00:00+08:00",
      generatedAt: "2026-08-26T12:00:00+08:00",
      entries: [{ rank: 1, memberId: 7, memberName: "真实玩家", avatarId: "nova", points: 88, completedGames: 2 }],
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createPlatformApiClient().getLeaderboard("month")).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/leaderboard?period=month",
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

  it("renames a room through the IP-identified endpoint", async () => {
    const response = { ip: "192.168.1.25", roomName: "A区游戏桌", online: false };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(createPlatformApiClient().renameRoom("192.168.1.25", "A区游戏桌")).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/rooms/192.168.1.25",
      expect.objectContaining({ method: "PUT", body: JSON.stringify({ roomName: "A区游戏桌" }) }),
    );
  });

  it("preserves the optional global game-time contract in room snapshots", async () => {
    const response = [{
      ip: "192.168.1.25", deviceId: "game-01", roomId: "room-01", roomName: "A区游戏桌",
      connectionId: "connection-1", online: true,
      state: { engineState: "RUNNING", gameTime: { mode: "LIMITED", remainingMillis: 61_000, running: true } },
      lastSequence: 4, lastEventType: "GAME_STARTED", lastEventAt: "2026-08-09T12:00:00.000Z", queueLength: 0,
    }];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 })));

    await expect(createPlatformApiClient().listRooms()).resolves.toEqual(response);
  });

  it("soft deletes a member by immutable database id", async () => {
    const response = { id: 42, phone: "13800138000", status: "DELETED", deletedAt: "2026-08-26T12:00:00Z" };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createPlatformApiClient().deleteMember(42)).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/members/42",
      expect.objectContaining({ method: "DELETE", headers: expect.any(Headers) }),
    );
  });

  it("preserves a stable member deletion conflict", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "MEMBER_HAS_OPEN_WRISTBAND", message: "请先解除手环绑定" }),
      { status: 409 },
    )));

    const error = await createPlatformApiClient().deleteMember(42).catch((reason) => reason);
    expect(error).toMatchObject({ status: 409, code: "MEMBER_HAS_OPEN_WRISTBAND", message: "请先解除手环绑定" });
  });

  it("loads typed real dashboard metrics", async () => {
    const response = {
      totalMembers: 12,
      newMembersToday: 2,
      wristbandsChargedToday: 3,
      revenueTodayCents: 15000,
      periodStart: "2026-08-26T00:00:00+08:00",
      periodEnd: "2026-08-27T00:00:00+08:00",
      generatedAt: "2026-08-26T12:00:00+08:00",
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createPlatformApiClient().getDashboardOverview()).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/dashboard/overview",
      expect.objectContaining({ headers: expect.any(Headers) }),
    );
  });
});
