export interface PlatformApiClientOptions {
  baseUrl?: string;
}

export interface PlatformApiClient {
  request<TResponse = unknown>(
    path: string,
    options?: RequestInit,
  ): Promise<TResponse | null>;
  getPlayerInfo(phone: string): Promise<PlayerInfo>;
  listRooms(): Promise<RoomStatus[]>;
}

interface ErrorResponse {
  code?: string;
  message?: string;
}

export interface PlayerProfile {
  id: number;
  phone: string;
  name: string;
  avatarId?: string | null;
  birthday?: string | null;
  gender?: string | null;
  status: "ACTIVE" | "FROZEN";
  createdAt: string;
  createdBy: string;
}

export interface PlayerWristband {
  uid: string;
  status: "READY" | "ACTIVE" | "EXPIRED";
  durationMinutes: number;
  startedAt: string | null;
  expiresAt: string | null;
  remainingSeconds: number;
}

export interface PlayerRecentPlay {
  id: number;
  gameId: string;
  gameName: string;
  deviceId: string;
  roomId?: string | null;
  status: "RUNNING" | "COMPLETED" | "ABORTED";
  startedAt: string;
  endedAt?: string | null;
  success?: boolean | null;
  terminationReason?: string | null;
  rawScore?: number | null;
  pointsAwarded: number;
}

export interface PlayerInfo {
  profile: PlayerProfile;
  points: { total: number; rank: number };
  wristbands: PlayerWristband[];
  recentPlays: PlayerRecentPlay[];
}

export interface RoomStatus {
  ip: string;
  deviceId: string;
  roomId: string;
  roomName: string;
  connectionId: string;
  online: boolean;
  state: Record<string, unknown>;
  lastSequence: number;
  lastEventType: string | null;
  lastEventAt: string | null;
  queueLength: number;
}

export class PlatformApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string = "REQUEST_FAILED",
  ) {
    super(message);
    this.name = "PlatformApiError";
  }
}

export function createPlatformApiClient({
  baseUrl = "http://127.0.0.1:8090",
}: PlatformApiClientOptions = {}): PlatformApiClient {
  const normalizedBaseUrl = baseUrl.replace(/\/$/, "");

  const client: PlatformApiClient = {
    async request<TResponse = unknown>(
      path: string,
      options: RequestInit = {},
    ): Promise<TResponse | null> {
      const headers = new Headers(options.headers);
      if (!headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }

      const response = await fetch(`${normalizedBaseUrl}${path}`, {
        ...options,
        headers,
      });
      const text = await response.text();
      const data = text ? (JSON.parse(text) as unknown) : null;

      if (!response.ok) {
        const message =
          typeof data === "object" &&
          data !== null &&
          "message" in data &&
          typeof (data as ErrorResponse).message === "string"
            ? (data as ErrorResponse).message!
            : `Platform request failed: ${response.status}`;
        const code =
          typeof data === "object" &&
          data !== null &&
          "code" in data &&
          typeof (data as ErrorResponse).code === "string"
            ? (data as ErrorResponse).code!
            : "REQUEST_FAILED";
        throw new PlatformApiError(message, response.status, code);
      }

      return data as TResponse | null;
    },
    async getPlayerInfo(phone: string): Promise<PlayerInfo> {
      const normalizedPhone = phone.replace(/\D/g, "");
      const result = await client.request<PlayerInfo>(
        `/api/player-info?phone=${encodeURIComponent(normalizedPhone)}`,
      );
      if (!result) {
        throw new PlatformApiError("会员信息响应为空", 502, "EMPTY_RESPONSE");
      }
      return result;
    },
    async listRooms(): Promise<RoomStatus[]> {
      const result = await client.request<RoomStatus[]>("/api/rooms");
      return Array.isArray(result) ? result : [];
    },
  };
  return client;
}
