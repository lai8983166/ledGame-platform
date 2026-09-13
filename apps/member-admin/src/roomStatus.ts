import type { RoomStatus } from "@ledgame/platform-api-client";
import type { HardwareDevice, Room } from "./types";

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

function normalizeHardware(value: unknown, online: boolean): HardwareDevice[] {
  if (Array.isArray(value)) {
    const devices = value
      .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object")
      .map((item, index) => {
        const rawStatus = String(item.status || "unknown").toLowerCase();
        const status: HardwareDevice["status"] = rawStatus === "online"
          || rawStatus === "warning" || rawStatus === "offline" || rawStatus === "unknown"
          ? rawStatus
          : "unknown";
        return {
          id: String(item.id || `hardware-${index + 1}`),
          name: String(item.name || "ELC-408 controller"),
          location: String(item.location || "Game terminal"),
          status,
          detail: String(item.detail || "No hardware status reported"),
        };
      });
    if (devices.length) return devices;
  }
  return [{
    id: "elc408-controller",
    name: "ELC-408 controller",
    location: "Game terminal",
    status: online === false ? "offline" : "unknown",
    detail: online === false ? "Game terminal is offline" : "Waiting for the first controller search",
  }];
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
    hardware: normalizeHardware(state.hardware, source.online),
  };
}
