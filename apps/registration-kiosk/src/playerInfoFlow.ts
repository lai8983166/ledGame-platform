import { reactive } from "vue";
import type { PlayerInfo } from "@ledgame/platform-api-client";

export interface PlayerInfoClient {
  getPlayerInfo(phone: string): Promise<PlayerInfo>;
}

export interface PlayerInfoFlowState {
  phone: string;
  status: "idle" | "loading" | "success" | "error";
  info: PlayerInfo | null;
  error: string;
}

export function createPlayerInfoFlow(client: PlayerInfoClient) {
  const state = reactive<PlayerInfoFlowState>({
    phone: "",
    status: "idle",
    info: null,
    error: "",
  });

  const query = async () => {
    const phone = state.phone.replace(/\D/g, "");
    state.info = null;
    state.error = "";
    if (!/^\d{7,15}$/.test(phone)) {
      state.status = "error";
      state.error = "Enter 7–15 digits to continue.";
      return;
    }
    state.phone = phone;
    state.status = "loading";
    try {
      state.info = await client.getPlayerInfo(phone);
      state.status = "success";
    } catch (error) {
      state.info = null;
      state.status = "error";
      state.error = error instanceof Error ? error.message : "无法连接本机服务";
    }
  };

  const reset = () => {
    state.phone = "";
    state.status = "idle";
    state.info = null;
    state.error = "";
  };

  return { state, query, reset };
}
