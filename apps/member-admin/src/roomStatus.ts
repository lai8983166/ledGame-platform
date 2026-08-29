import type { RoomStatus } from "@ledgame/platform-api-client";
import type { Room } from "./types";

type NormalizedGameTime = {
  mode: "LIMITED" | "UNLIMITED";
  remainingMillis: number | null;
  running: boolean;
};

function normalizeGameTime(value: unknown): NormalizedGameTime | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as Record<string, unknown>;
  const mode = String(candidate.mode || "").toUpperCase();
  if (mode === "UNLIMITED") {
    return { mode, remainingMillis: null, running: candidate.running === true };
  }
  const remainingMillis = Number(candidate.remainingMillis);
  if (mode !== "LIMITED" || !Number.isFinite(remainingMillis)) return null;
  return {
    mode,
    remainingMillis: Math.max(0, remainingMillis),
    running: candidate.running === true,
  };
}

export function formatGameTime(remainingMillis: number): string {
  const totalSeconds = Math.ceil(Math.max(0, remainingMillis) / 1000);
  const seconds = totalSeconds % 60;
  const minutes = Math.floor(totalSeconds / 60) % 60;
  const hours = Math.floor(totalSeconds / 3600);
  const mmss = `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  return hours > 0 ? `${String(hours).padStart(2, "0")}:${mmss}` : mmss;
}

export function roomGameTimeText(room: Room, nowMillis = Date.now(), unlimitedLabel = "无限"): string | null {
  if (room.status !== "playing") return null;
  if (!room.gameTimeMode) return "--";
  if (room.gameTimeMode === "UNLIMITED") return unlimitedLabel;

  let remainingMillis = Math.max(0, Number(room.gameTimeRemainingMillis) || 0);
  if (room.gameTimeRunning && room.lastEventAt) {
    const observedAt = Date.parse(room.lastEventAt);
    if (Number.isFinite(observedAt)) {
      remainingMillis = Math.max(0, remainingMillis - Math.max(0, nowMillis - observedAt));
    }
  }
  return formatGameTime(remainingMillis);
}

export function mapRoomStatus(source: RoomStatus): Room {
  const state = source.state || {};
  const engineState = String(state.engineState || "IDLE").toUpperCase();
  const playing = engineState === "RUNNING" || engineState === "STARTING" || engineState === "SETTLING";
  const gameTime = normalizeGameTime(state.gameTime);
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
    gameTimeMode: gameTime?.mode,
    gameTimeRemainingMillis: gameTime?.remainingMillis,
    gameTimeRunning: gameTime?.running,
    players: [],
    hardware: [],
  };
}
