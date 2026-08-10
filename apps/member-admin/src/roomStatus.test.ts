import { describe, expect, it } from "vitest";
import { mapRoomStatus } from "./roomStatus";

describe("mapRoomStatus", () => {
  it("maps a running online room and preserves queue data", () => {
    const room = mapRoomStatus({
      ip: "192.168.1.25",
      deviceId: "game-01",
      roomId: "room-01",
      roomName: "Room 01",
      connectionId: "connection-1",
      online: true,
      state: { engineState: "RUNNING", gameName: "Color Rush" },
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
});
