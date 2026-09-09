import { reactive } from "vue";
import type { PlayerInfo, PlayerInfoQuery } from "@ledgame/platform-api-client";

export type PlayerInfoMode = "phone" | "wristband";

export interface PlayerInfoClient {
  getPlayerInfo(query: string | PlayerInfoQuery): Promise<PlayerInfo>;
}

export interface PlayerInfoFlowState {
  mode: PlayerInfoMode;
  phone: string;
  wristbandUid: string;
  status: "idle" | "loading" | "success" | "error";
  info: PlayerInfo | null;
  error: string;
}

export function createPlayerInfoFlow(client: PlayerInfoClient) {
  let queryRevision = 0;
  const state = reactive<PlayerInfoFlowState>({
    mode: "phone",
    phone: "",
    wristbandUid: "",
    status: "idle",
    info: null,
    error: "",
  });

  const query = async () => {
    const revision = ++queryRevision;
    const phone = state.phone.replace(/\D/g, "");
    const wristbandUid = state.wristbandUid.trim();
    state.info = null;
    state.error = "";

    if (state.mode === "phone") {
      if (!/^\d{7,15}$/.test(phone)) {
        state.status = "error";
        state.error = "Enter 7-15 digits to continue.";
        return;
      }
      state.phone = phone;
    } else {
      if (!/^\d{1,32}$/.test(wristbandUid)) {
        state.status = "error";
        state.error = "Enter a valid wristband UID to continue.";
        return;
      }
      state.wristbandUid = wristbandUid;
    }

    state.status = "loading";
    try {
      const info = state.mode === "phone"
        ? await client.getPlayerInfo(phone)
        : await client.getPlayerInfo({ wristbandUid });
      if (revision !== queryRevision) return;
      state.info = info;
      state.status = "success";
    } catch (error) {
      if (revision !== queryRevision) return;
      state.info = null;
      state.status = "error";
      state.error = error instanceof Error ? error.message : "Member Admin connection failed.";
    }
  };

  const reset = () => {
    queryRevision += 1;
    state.mode = "phone";
    state.phone = "";
    state.wristbandUid = "";
    state.status = "idle";
    state.info = null;
    state.error = "";
  };

  return { state, query, reset };
}
