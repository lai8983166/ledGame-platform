import type { KioskScreen } from "./types";

export type KioskFlowKey = "activation" | "wristband" | "success" | "playerInfo";
export type FlowTimeouts = Record<KioskFlowKey, number>;

export const REGISTRATION_KIOSK_TIMEOUTS_STORAGE_KEY = "ledgame.registration-kiosk.flow-timeouts";
export const FLOW_TIMEOUT_MIN_SECONDS = 10;
export const FLOW_TIMEOUT_MAX_SECONDS = 600;
export const DEFAULT_FLOW_TIMEOUTS: FlowTimeouts = {
  activation: 60,
  wristband: 45,
  success: 30,
  playerInfo: 60,
};

export function normalizeFlowTimeouts(raw: unknown): FlowTimeouts {
  const source = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
  return Object.fromEntries(
    (Object.keys(DEFAULT_FLOW_TIMEOUTS) as KioskFlowKey[]).map((key) => {
      const value = Number(source[key]);
      const normalized = Number.isInteger(value) && value >= FLOW_TIMEOUT_MIN_SECONDS && value <= FLOW_TIMEOUT_MAX_SECONDS
        ? value
        : DEFAULT_FLOW_TIMEOUTS[key];
      return [key, normalized];
    }),
  ) as FlowTimeouts;
}

export function readFlowTimeouts(storage: Pick<Storage, "getItem" | "setItem">): FlowTimeouts {
  try {
    const stored = storage.getItem(REGISTRATION_KIOSK_TIMEOUTS_STORAGE_KEY);
    return normalizeFlowTimeouts(stored ? JSON.parse(stored) : null);
  } catch {
    const defaults = { ...DEFAULT_FLOW_TIMEOUTS };
    try {
      storage.setItem(REGISTRATION_KIOSK_TIMEOUTS_STORAGE_KEY, JSON.stringify(defaults));
    } catch {
      // A kiosk can continue with defaults even when local storage is unavailable.
    }
    return defaults;
  }
}

export function writeFlowTimeouts(storage: Pick<Storage, "setItem">, value: unknown): FlowTimeouts {
  const normalized = normalizeFlowTimeouts(value);
  storage.setItem(REGISTRATION_KIOSK_TIMEOUTS_STORAGE_KEY, JSON.stringify(normalized));
  return normalized;
}

export function flowForScreen(screen: KioskScreen): KioskFlowKey | null {
  if (["phone", "confirm", "register"].includes(screen)) return "activation";
  if (screen === "swipe") return "wristband";
  if (screen === "success") return "success";
  if (["info-phone", "info-result"].includes(screen)) return "playerInfo";
  return null;
}

export interface FlowTimeoutControllerOptions {
  getTimeout: (flow: KioskFlowKey) => number;
  onTick: (remainingSeconds: number, flow: KioskFlowKey) => void;
  onTimeout: (flow: KioskFlowKey) => void;
  setInterval?: typeof window.setInterval;
  clearInterval?: typeof window.clearInterval;
}

export function createFlowTimeoutController(options: FlowTimeoutControllerOptions) {
  const setIntervalFn = options.setInterval ?? (globalThis.setInterval.bind(globalThis) as unknown as typeof window.setInterval);
  const clearIntervalFn = options.clearInterval ?? (globalThis.clearInterval.bind(globalThis) as unknown as typeof window.clearInterval);
  let timer: number | undefined;
  let flow: KioskFlowKey | null = null;
  let remaining = 0;

  const stop = () => {
    if (timer !== undefined) clearIntervalFn(timer);
    timer = undefined;
    flow = null;
    remaining = 0;
  };

  const start = (nextFlow: KioskFlowKey) => {
    stop();
    flow = nextFlow;
    remaining = Math.max(FLOW_TIMEOUT_MIN_SECONDS, Math.floor(options.getTimeout(nextFlow)));
    options.onTick(remaining, nextFlow);
    timer = setIntervalFn(() => {
      if (!flow) return;
      remaining -= 1;
      options.onTick(Math.max(remaining, 0), flow);
      if (remaining <= 0) {
        const expiredFlow = flow;
        stop();
        options.onTimeout(expiredFlow);
      }
    }, 1000) as unknown as number;
  };

  return { start, stop, getRemaining: () => remaining, getFlow: () => flow };
}
