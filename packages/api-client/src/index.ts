export interface PlatformApiClientOptions {
  baseUrl?: string;
  transport?: PlatformApiTransport;
}

export interface PlatformApiTransportRequest {
  path: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

export interface PlatformApiTransportResponse {
  status: number;
  body: string;
}

export type PlatformApiTransport = (
  request: PlatformApiTransportRequest,
) => Promise<PlatformApiTransportResponse>;

export interface PlatformApiClient {
  request<TResponse = unknown>(
    path: string,
    options?: RequestInit,
  ): Promise<TResponse | null>;
  getPlayerInfo(phone: string): Promise<PlayerInfo>;
  getLeaderboard(period: LeaderboardPeriod): Promise<LeaderboardResponse>;
  getDashboardOverview(): Promise<DashboardOverview>;
  deleteMember(id: number): Promise<DeletedMember>;
  listRooms(): Promise<RoomStatus[]>;
  renameRoom(ip: string, roomName: string): Promise<RoomStatus>;
}

export interface DeletedMember {
  id: number;
  phone: string;
  name: string;
  status: "DELETED";
  deletedAt: string;
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
  scoringPolicy?: string | null;
}

export interface PlayerInfo {
  profile: PlayerProfile;
  points: { total: number; rank: number };
  wristbands: PlayerWristband[];
  recentPlays: PlayerRecentPlay[];
}

export type LeaderboardPeriod = "day" | "month" | "year";

export interface LeaderboardEntry {
  rank: number;
  memberId: number;
  memberName: string;
  avatarId?: string | null;
  points: number;
  completedGames: number;
}

export interface LeaderboardResponse {
  period: LeaderboardPeriod;
  periodStart: string;
  periodEnd: string;
  generatedAt: string;
  entries: LeaderboardEntry[];
}

export interface DashboardOverview {
  totalMembers: number;
  newMembersToday: number;
  wristbandsChargedToday: number;
  revenueTodayCents: number;
  periodStart: string;
  periodEnd: string;
  generatedAt: string;
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

export const DEFAULT_PLATFORM_BASE_URL = "http://127.0.0.1:8090";

export function resolvePlatformBaseUrl(value?: string | null): string {
  const candidate = String(value || "").trim();
  if (!candidate) return DEFAULT_PLATFORM_BASE_URL;
  try {
    const url = new URL(candidate);
    if ((url.protocol !== "http:" && url.protocol !== "https:") || url.username || url.password) {
      return DEFAULT_PLATFORM_BASE_URL;
    }
    url.hash = "";
    url.search = "";
    url.pathname = url.pathname.replace(/\/+$/, "") || "/";
    return url.toString().replace(/\/$/, "");
  } catch {
    return DEFAULT_PLATFORM_BASE_URL;
  }
}

export function createPlatformApiClient({
  baseUrl = DEFAULT_PLATFORM_BASE_URL,
  transport,
}: PlatformApiClientOptions = {}): PlatformApiClient {
  const normalizedBaseUrl = resolvePlatformBaseUrl(baseUrl);

  const client: PlatformApiClient = {
    async request<TResponse = unknown>(
      path: string,
      options: RequestInit = {},
    ): Promise<TResponse | null> {
      const headers = new Headers(options.headers);
      if (!headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }

      const method = String(options.method || "GET").toUpperCase();
      const transportResponse = transport
        ? await transport({
            path,
            method,
            headers: Object.fromEntries(headers.entries()),
            ...(typeof options.body === "string" ? { body: options.body } : {}),
          })
        : await fetch(`${normalizedBaseUrl}${path}`, {
            ...options,
            method,
            headers,
          }).then(async (response) => ({ status: response.status, body: await response.text() }));
      const text = transportResponse.body;
      const data = text ? (JSON.parse(text) as unknown) : null;

      if (transportResponse.status < 200 || transportResponse.status >= 300) {
        const message =
          typeof data === "object" &&
          data !== null &&
          "message" in data &&
          typeof (data as ErrorResponse).message === "string"
            ? (data as ErrorResponse).message!
            : `Platform request failed: ${transportResponse.status}`;
        const code =
          typeof data === "object" &&
          data !== null &&
          "code" in data &&
          typeof (data as ErrorResponse).code === "string"
            ? (data as ErrorResponse).code!
            : "REQUEST_FAILED";
        throw new PlatformApiError(message, transportResponse.status, code);
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
    async getLeaderboard(period: LeaderboardPeriod): Promise<LeaderboardResponse> {
      const result = await client.request<LeaderboardResponse>(
        `/api/leaderboard?period=${encodeURIComponent(period)}`,
      );
      if (!result) {
        throw new PlatformApiError("排行榜响应为空", 502, "EMPTY_RESPONSE");
      }
      return result;
    },
    async getDashboardOverview(): Promise<DashboardOverview> {
      const result = await client.request<DashboardOverview>("/api/dashboard/overview");
      if (!result) {
        throw new PlatformApiError("运营总览响应为空", 502, "EMPTY_RESPONSE");
      }
      return result;
    },
    async deleteMember(id: number): Promise<DeletedMember> {
      if (!Number.isInteger(id) || id <= 0) {
        throw new PlatformApiError("会员 ID 无效", 400, "INVALID_MEMBER_ID");
      }
      const result = await client.request<DeletedMember>(
        `/api/members/${encodeURIComponent(String(id))}`,
        { method: "DELETE" },
      );
      if (!result) {
        throw new PlatformApiError("删除会员响应为空", 502, "EMPTY_RESPONSE");
      }
      return result;
    },
    async listRooms(): Promise<RoomStatus[]> {
      const result = await client.request<RoomStatus[]>("/api/rooms");
      return Array.isArray(result) ? result : [];
    },
    async renameRoom(ip: string, roomName: string): Promise<RoomStatus> {
      const result = await client.request<RoomStatus>(`/api/rooms/${encodeURIComponent(ip)}`, {
        method: "PUT",
        body: JSON.stringify({ roomName }),
      });
      if (!result) {
        throw new PlatformApiError("Room rename response was empty", 502, "EMPTY_RESPONSE");
      }
      return result;
    },
  };
  return client;
}
