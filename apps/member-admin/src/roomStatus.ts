import type { RoomStatus } from "@ledgame/platform-api-client";
import type { Room } from "./types";

export function mapRoomStatus(source: RoomStatus): Room {
  const state = source.state || {};
  const engineState = String(state.engineState || "IDLE").toUpperCase();
  const playing = engineState === "RUNNING" || engineState === "STARTING" || engineState === "SETTLING";
  return {
    id: source.ip,
    code: source.ip,
    name: source.roomName || source.roomId || source.ip,
    status: playing ? "playing" : "idle",
    online: source.online,
    ip: source.ip,
    lastEventType: source.lastEventType,
    lastEventAt: source.lastEventAt,
    connectionId: source.connectionId,
    lastSequence: source.lastSequence,
    queueLength: source.queueLength,
    gameName: typeof state.gameName === "string" ? state.gameName : undefined,
    phase: engineState,
    remainingSeconds: 0,
    players: [],
    hardware: [],
  };
}
