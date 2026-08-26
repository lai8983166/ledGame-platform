import type {
  LeaderboardPeriod,
  LeaderboardResponse,
} from "@ledgame/platform-api-client";

export type LeaderboardLoadStatus = "idle" | "loading" | "success" | "error";

export interface LeaderboardViewState {
  period: LeaderboardPeriod;
  status: LeaderboardLoadStatus;
  data: LeaderboardResponse | null;
  error: string;
  requestRevision: number;
}
export function createLeaderboardState(): LeaderboardViewState {
  return { period: "day", status: "idle", data: null, error: "", requestRevision: 0 };
}

export async function loadLeaderboard(
  state: LeaderboardViewState,
  period: LeaderboardPeriod,
  fetchLeaderboard: (period: LeaderboardPeriod) => Promise<LeaderboardResponse>,
): Promise<void> {
  const revision = ++state.requestRevision;
  state.period = period;
  state.status = "loading";
  state.data = null;
  state.error = "";
  try {
    const response = await fetchLeaderboard(period);
    if (state.requestRevision !== revision) return;
    state.data = response;
    state.status = "success";
  } catch (error) {
    if (state.requestRevision !== revision) return;
    state.data = null;
    state.status = "error";
    state.error = error instanceof Error ? error.message : "排行榜加载失败";
  }
}
