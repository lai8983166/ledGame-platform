export type PageId = "overview" | "rooms" | "members" | "records" | "ranking" | "settings";

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
  remainingSeconds?: number;
  players: LivePlayer[];
  hardware: HardwareDevice[];
}

export interface Member {
  id: string;
  account: string;
  name: string;
  initials: string;
  phone: string;
  identityId: string;
  rechargeAmount: number;
  braceletMinutes: number;
  status: "active" | "inactive";
  joinedAt: string;
  color: string;
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

export type RankingPeriod = "day" | "month" | "year";

export interface RankEntry {
  rank: number;
  memberName: string;
  account: string;
  initials: string;
  score: number;
  games: number;
  trend: number;
  color: string;
}

export interface FeatureSetting {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  critical?: boolean;
}
