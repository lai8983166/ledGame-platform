export type PageId = "wristbands" | "overview" | "rooms" | "members" | "records" | "ranking" | "settings";

export type StatusTone = "neutral" | "info" | "success" | "warning" | "danger" | "purple";

export interface HardwareDevice {
  id: string;
  name: string;
  location: string;
  status: "online" | "warning" | "offline";
  detail: string;
}

export interface LivePlayer {
  id: string;
  name: string;
  initials: string;
  score: number;
  rank: number;
  color: string;
}

export interface Room {
  id: string;
  name: string;
  code: string;
  status: "idle" | "playing";
  gameName?: string;
  phase?: string;
  gameTimeMode?: "LIMITED" | "UNLIMITED";
  gameTimeRemainingMillis?: number | null;
  gameTimeRunning?: boolean;
  players: LivePlayer[];
  hardware: HardwareDevice[];
  ip?: string;
  online?: boolean;
  connectionId?: string;
  lastSequence?: number;
  lastEventType?: string | null;
  lastEventAt?: string | null;
  queueLength?: number;
}

export interface Member {
  id: string;
  account: string;
  name: string;
  initials: string;
  phone: string;
  identityId: string;
  status: "active" | "inactive";
  joinedAt: string;
  color: string;
  pointsTotal?: number;
  rank?: number;
}

export type WristbandState = "empty" | "charged" | "ready" | "active" | "expired";

export interface Wristband {
  uid: string;
  state: WristbandState;
  durationMinutes: number | null;
  memberId: string | null;
  chargedAt: string | null;
  activatedAt: string | null;
  startedAt: string | null;
}

export interface CardIssueRecord {
  id: string;
  braceletId: string;
  memberName: string;
  memberAccount: string;
  issuedAt: string;
  duration: number;
  status: "activated" | "unused" | "expired";
}

export interface PlayRecord {
  id: string;
  memberName: string;
  braceletId: string;
  roomName: string;
  score: number;
  pointsAwarded?: number;
  scoringPolicy?: string | null;
  status?: string;
  terminationReason?: string | null;
  startedAt: string;
  endedAt: string;
}

export interface TransactionRecord {
  id: string;
  memberName: string;
  memberAccount: string;
  amount: number;
  tradedAt: string;
  status: "success" | "refunded" | "pending";
}

export interface GameConfig {
  id: string;
  name: string;
  category: string;
  levels: string[];
  lives: number;
  scoringRule: string;
  assetPath: string;
  components: string[];
  status: "enabled" | "disabled";
}

export interface FeatureSetting {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  critical?: boolean;
}
