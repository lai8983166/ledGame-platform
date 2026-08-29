import { describe, expect, it } from "vitest";
import { formatGameTime, mapRoomStatus, roomGameTimeText } from "./roomStatus";

describe("mapRoomStatus", () => {
  it("maps a running online room and preserves queue data", () => {
    const room = mapRoomStatus({
      ip: "192.168.1.25",
      deviceId: "game-01",
      roomId: "room-01",
      roomName: "Room 01",
      connectionId: "connection-1",
      online: true,
      state: {
        engineState: "RUNNING",
        gameName: "Color Rush",
        gameTime: { mode: "LIMITED", remainingMillis: 61_000, running: true },
      },
      lastSequence: 4,
      lastEventType: "GAME_STARTED",
      lastEventAt: "2026-08-09T12:00:00Z",
      queueLength: 2,
    });

    expect(room).toMatchObject({
      id: "192.168.1.25",
      status: "playing",
      online: true,
      gameName: "Color Rush",
      queueLength: 2,
      gameTimeMode: "LIMITED",
      gameTimeRemainingMillis: 61_000,
      gameTimeRunning: true,
    });
  });

  it("maps a disconnected room without inventing gameplay data", () => {
    const room = mapRoomStatus({
      ip: "192.168.1.26",
      deviceId: "game-02",
      roomId: "room-02",
      roomName: "",
      connectionId: "connection-2",
      online: false,
      state: {},
      lastSequence: 1,
      lastEventType: "ROOM_SNAPSHOT",
      lastEventAt: "2026-08-09T12:00:00Z",
      queueLength: 0,
    });

    expect(room).toMatchObject({ id: "192.168.1.26", name: "room-02", status: "idle", online: false });
    expect(room.gameName).toBeUndefined();
  });

  it("applies a reconnect snapshot and event-driven idle transition", () => {
    const room = mapRoomStatus({
      ip: "192.168.1.26",
      deviceId: "game-02",
      roomId: "room-02",
      roomName: "Room 02",
      connectionId: "connection-new",
      online: true,
      state: { engineState: "IDLE", queueSummary: { waiting: [] } },
      lastSequence: 8,
      lastEventType: "ROOM_SNAPSHOT",
      lastEventAt: "2026-08-09T12:01:00Z",
      queueLength: 0,
    });

    expect(room).toMatchObject({
      online: true,
      status: "idle",
      connectionId: "connection-new",
      lastEventType: "ROOM_SNAPSHOT",
      lastSequence: 8,
    });
  });

  it("derives a finite running countdown from the platform event anchor", () => {
    const room = mapRoomStatus({
      ip: "192.168.1.25", deviceId: "game-01", roomId: "room-01", roomName: "Room 01",
      connectionId: "connection-1", online: true,
      state: { engineState: "RUNNING", gameTime: { mode: "LIMITED", remainingMillis: 61_000, running: true } },
      lastSequence: 4, lastEventType: "GAME_STARTED", lastEventAt: "2026-08-09T12:00:00.000Z", queueLength: 0,
    });

    expect(roomGameTimeText(room, Date.parse("2026-08-09T12:00:01.100Z"))).toBe("01:00");
    expect(roomGameTimeText(room, Date.parse("2026-08-09T12:02:00.000Z"))).toBe("00:00");
  });

  it("freezes paused finite time and renders unlimited time explicitly", () => {
    const paused = mapRoomStatus({
      ip: "192.168.1.25", deviceId: "game-01", roomId: "room-01", roomName: "Room 01",
      connectionId: "connection-1", online: true,
      state: { engineState: "SETTLING", gameTime: { mode: "LIMITED", remainingMillis: 61_000, running: false } },
      lastSequence: 5, lastEventType: "GAME_TIMING_CHANGED", lastEventAt: "2026-08-09T12:00:00.000Z", queueLength: 0,
    });
    const unlimited = { ...paused, gameTimeMode: "UNLIMITED" as const, gameTimeRemainingMillis: null, gameTimeRunning: true };

    expect(roomGameTimeText(paused, Date.parse("2026-08-09T12:10:00.000Z"))).toBe("01:01");
    expect(roomGameTimeText(unlimited, Date.parse("2026-08-09T12:10:00.000Z"))).toBe("无限");
  });

  it("uses a compatibility placeholder for an active legacy snapshot and hides idle time", () => {
    const activeLegacy = mapRoomStatus({
      ip: "192.168.1.27", deviceId: "game-03", roomId: "room-03", roomName: "Room 03",
      connectionId: "connection-3", online: true, state: { engineState: "RUNNING" },
      lastSequence: 1, lastEventType: "ROOM_SNAPSHOT", lastEventAt: "2026-08-09T12:00:00.000Z", queueLength: 0,
    });

    expect(roomGameTimeText(activeLegacy)).toBe("--");
    expect(roomGameTimeText({ ...activeLegacy, status: "idle" })).toBeNull();
  });

  it("formats zero, partial seconds and durations over an hour", () => {
    expect(formatGameTime(0)).toBe("00:00");
    expect(formatGameTime(1)).toBe("00:01");
    expect(formatGameTime(999)).toBe("00:01");
    expect(formatGameTime(60_000)).toBe("01:00");
    expect(formatGameTime(3_661_000)).toBe("01:01:01");
  });
});
