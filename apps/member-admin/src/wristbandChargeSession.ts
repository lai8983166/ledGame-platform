export type WristbandChargeStatus = "idle" | "scanning" | "checking" | "details" | "submitting";

export interface WristbandChargeSession {
  status: WristbandChargeStatus;
  uid: string;
  buffer: string;
  minutes: number;
  error: string;
  revision: number;
}

export interface WristbandChargeToken {
  revision: number;
  uid: string;
}

export interface WristbandChargeRequest extends WristbandChargeToken {
  minutes: number;
}

export function createWristbandChargeSession(): WristbandChargeSession {
  return { status: "idle", uid: "", buffer: "", minutes: 60, error: "", revision: 0 };
}

function reset(state: WristbandChargeSession, status: WristbandChargeStatus): void {
  state.status = status;
  state.uid = "";
  state.buffer = "";
  state.minutes = 60;
  state.error = "";
}

export function startChargeSession(state: WristbandChargeSession): void {
  state.revision += 1;
  reset(state, "scanning");
}

export function consumeChargeScanKey(state: WristbandChargeSession, key: string): WristbandChargeToken | null {
  if (state.status !== "scanning") return null;
  if (/^\d$/.test(key) && state.buffer.length < 32) {
    state.buffer += key;
    state.error = "";
    return null;
  }
  if (key === "Backspace") {
    state.buffer = state.buffer.slice(0, -1);
    return null;
  }
  if (key !== "Enter") return null;
  if (!/^\d{1,32}$/.test(state.buffer)) {
    state.error = "请刷手环后再继续";
    return null;
  }
  state.status = "checking";
  state.error = "";
  return { revision: state.revision, uid: state.buffer };
}

function isCurrentCheck(state: WristbandChargeSession, token: WristbandChargeToken): boolean {
  return state.status === "checking" && state.revision === token.revision;
}

export function acceptChargeScan(
  state: WristbandChargeSession,
  token: WristbandChargeToken,
  wristbandStatus: string | null,
): boolean {
  if (!isCurrentCheck(state, token)) return false;
  if (wristbandStatus !== null && wristbandStatus !== "IN_STOCK") {
    state.status = "scanning";
    state.uid = "";
    state.buffer = "";
    state.error = `该手环当前状态为 ${wristbandStatus}，不能重复充时`;
    return false;
  }
  state.status = "details";
  state.uid = token.uid;
  state.buffer = "";
  state.error = "";
  return true;
}

export function failChargeScan(
  state: WristbandChargeSession,
  token: WristbandChargeToken,
  message: string,
): boolean {
  if (!isCurrentCheck(state, token)) return false;
  state.status = "scanning";
  state.uid = "";
  state.buffer = "";
  state.error = message;
  return true;
}

export function cancelChargeSession(state: WristbandChargeSession): boolean {
  if (state.status === "submitting") return false;
  state.revision += 1;
  reset(state, "idle");
  return true;
}

export function setChargeMinutes(state: WristbandChargeSession, minutes: number): void {
  if (state.status !== "details") return;
  state.minutes = minutes;
  state.error = "";
}

export function beginChargeSubmit(state: WristbandChargeSession): WristbandChargeRequest | null {
  if (state.status !== "details") return null;
  if (!Number.isInteger(state.minutes) || state.minutes < 1 || state.minutes > 1440) {
    state.error = "购买分钟数必须是 1 到 1440 的整数";
    return null;
  }
  state.status = "submitting";
  state.error = "";
  return { revision: state.revision, uid: state.uid, minutes: state.minutes };
}

function isCurrentSubmit(state: WristbandChargeSession, request: WristbandChargeRequest): boolean {
  return state.status === "submitting" && state.revision === request.revision && state.uid === request.uid;
}

export function failChargeSubmit(state: WristbandChargeSession, request: WristbandChargeRequest, message: string): boolean {
  if (!isCurrentSubmit(state, request)) return false;
  state.status = "details";
  state.error = message;
  return true;
}

export function succeedChargeSubmit(state: WristbandChargeSession, request: WristbandChargeRequest): boolean {
  if (!isCurrentSubmit(state, request)) return false;
  state.revision += 1;
  reset(state, "idle");
  return true;
}
