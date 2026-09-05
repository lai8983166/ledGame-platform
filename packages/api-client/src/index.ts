export interface PlatformApiClientOptions {
  baseUrl?: string;
  transport?: PlatformApiTransport;
  operatorIdProvider?: () => number | null | undefined;
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
  loginOperator(username: string, password: string): Promise<OperatorProfile>;
  listOperatorAccounts(): Promise<OperatorAccount[]>;
  createOperatorAccount(input: CreateOperatorAccountInput): Promise<OperatorAccount>;
  updateOperatorAccount(id: number, input: UpdateOperatorAccountInput): Promise<OperatorAccount>;
  resetOperatorPassword(id: number, password: string): Promise<OperatorAccount>;
  setOperatorEnabled(id: number, enabled: boolean): Promise<OperatorAccount>;
  getFeatureSettings(): Promise<ChildModeSetting>;
  setChildMode(enabled: boolean): Promise<ChildModeSetting>;
  recordSystemSettingsChange(): Promise<void>;
  getDatabaseBackupStatus(): Promise<DatabaseBackupStatus>;
  listDatabaseBackupCandidates(): Promise<DatabaseBackupCandidate[]>;
  keepCurrentDatabase(): Promise<DatabaseBackupStatus>;
}

export type OperatorAccountType = "FACTORY_ADMIN" | "OPERATOR";

export interface OperatorProfile {
  id: number;
  username: string;
  displayName: string;
  accountType: OperatorAccountType;
}

export interface OperatorAccount extends OperatorProfile {
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateOperatorAccountInput {
  username: string;
  displayName: string;
  password: string;
}

export interface UpdateOperatorAccountInput {
  username: string;
  displayName: string;
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

export interface ChildModeSetting {
  childMode: boolean;
}

export type DatabaseBackupLifecycleState =
  | "CHECKING"
  | "READY_PROTECTED"
  | "READY_DEGRADED"
  | "MAINTENANCE_LOGIN_REQUIRED"
  | "IMPORTING"
  | "BLOCKED";

export interface DatabaseBackupStatus {
  state: DatabaseBackupLifecycleState;
  phase: string;
  protectedData: boolean;
  targetVolume: string | null;
  lastSuccessfulBackupAt: string | null;
  sourceRevision: number;
  backupRevision: number | null;
  errorCode: string | null;
  message: string;
}

export interface DatabaseBackupCandidate {
  candidateId: string;
  sourceType: "LATEST" | "HISTORY" | "EXTERNAL";
  revision: number;
  lastBusinessModifiedAt: string;
  generatedAt: string | null;
  fileSize: number;
  environment: "PRODUCTION" | "TEST" | "EXTERNAL";
  factoryAdminUsername: string;
  memberCount: number;
  valid: boolean;
}

export interface GameTimeState {
  mode: "LIMITED" | "UNLIMITED";
  remainingMillis: number | null;
  running: boolean;
}

export interface RoomRuntimeState extends Record<string, unknown> {
  engineState?: string;
  gameName?: string;
  gameTime?: GameTimeState | null;
}

export interface RoomStatus {
  ip: string;
  deviceId: string;
  roomId: string;
  roomName: string;
  connectionId: string;
  online: boolean;
  state: RoomRuntimeState;
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
  operatorIdProvider,
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
      const operatorId = operatorIdProvider?.();
      if (Number.isInteger(operatorId) && Number(operatorId) > 0) {
        headers.set("X-Operator-Id", String(operatorId));
      }
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
    async loginOperator(username: string, password: string): Promise<OperatorProfile> {
      return requireResponse(await client.request<OperatorProfile>("/api/operator-auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      }), "登录响应为空");
    },
    async listOperatorAccounts(): Promise<OperatorAccount[]> {
      const result = await client.request<OperatorAccount[]>("/api/operator-accounts");
      return Array.isArray(result) ? result : [];
    },
    async createOperatorAccount(input: CreateOperatorAccountInput): Promise<OperatorAccount> {
      return requireResponse(await client.request<OperatorAccount>("/api/operator-accounts", {
        method: "POST",
        body: JSON.stringify(input),
      }), "创建账号响应为空");
    },
    async updateOperatorAccount(id: number, input: UpdateOperatorAccountInput): Promise<OperatorAccount> {
      return requireResponse(await client.request<OperatorAccount>(`/api/operator-accounts/${id}`, {
        method: "PUT",
        body: JSON.stringify(input),
      }), "修改账号响应为空");
    },
    async resetOperatorPassword(id: number, password: string): Promise<OperatorAccount> {
      return requireResponse(await client.request<OperatorAccount>(`/api/operator-accounts/${id}/password`, {
        method: "PUT",
        body: JSON.stringify({ password }),
      }), "重设密码响应为空");
    },
    async setOperatorEnabled(id: number, enabled: boolean): Promise<OperatorAccount> {
      return requireResponse(await client.request<OperatorAccount>(`/api/operator-accounts/${id}/enabled`, {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }), "修改账号状态响应为空");
    },
    async getFeatureSettings(): Promise<ChildModeSetting> {
      return requireResponse(await client.request<ChildModeSetting>("/api/feature-settings"),
        "功能设置响应为空");
    },
    async setChildMode(enabled: boolean): Promise<ChildModeSetting> {
      return requireResponse(await client.request<ChildModeSetting>("/api/feature-settings/child-mode", {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }), "儿童模式设置响应为空");
    },
    async recordSystemSettingsChange(): Promise<void> {
      await client.request("/api/operator-actions/system-settings", { method: "POST" });
    },
    async getDatabaseBackupStatus(): Promise<DatabaseBackupStatus> {
      return requireResponse(await client.request<DatabaseBackupStatus>("/api/database-backup/status"),
        "数据库备份状态响应为空");
    },
    async listDatabaseBackupCandidates(): Promise<DatabaseBackupCandidate[]> {
      const result = await client.request<DatabaseBackupCandidate[]>("/api/database-backup/candidates");
      return Array.isArray(result) ? result : [];
    },
    async keepCurrentDatabase(): Promise<DatabaseBackupStatus> {
      return requireResponse(await client.request<DatabaseBackupStatus>(
        "/api/database-backup/conflicts/use-current", { method: "POST" }),
      "保留当前数据库响应为空");
    },
  };
  return client;
}

function requireResponse<T>(value: T | null, message: string): T {
  if (value === null) {
    throw new PlatformApiError(message, 502, "EMPTY_RESPONSE");
  }
  return value;
}
