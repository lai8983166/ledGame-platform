import { describe, expect, it, vi } from "vitest";
import {
  DEFAULT_FLOW_TIMEOUTS,
  FLOW_TIMEOUT_MAX_SECONDS,
  FLOW_TIMEOUT_MIN_SECONDS,
  createFlowTimeoutController,
  flowForScreen,
  normalizeFlowTimeouts,
  readFlowTimeouts,
  writeFlowTimeouts,
} from "./flowTimeouts";

describe("registration kiosk flow timeouts", () => {
  it("normalizes invalid and out-of-range settings to safe defaults", () => {
    expect(normalizeFlowTimeouts({ activation: 5, wristband: 601, success: "bad", playerInfo: 45 })).toEqual({
      ...DEFAULT_FLOW_TIMEOUTS,
      playerInfo: 45,
    });
    expect(FLOW_TIMEOUT_MIN_SECONDS).toBe(10);
    expect(FLOW_TIMEOUT_MAX_SECONDS).toBe(600);
  });

  it("persists valid settings and recovers malformed storage", () => {
    const storage = new Map<string, string>();
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    };
    expect(writeFlowTimeouts(adapter, { activation: 90 })).toMatchObject({ activation: 90 });
    expect(readFlowTimeouts(adapter).activation).toBe(90);
    storage.set("ledgame.registration-kiosk.flow-timeouts", "not-json");
    expect(readFlowTimeouts(adapter)).toEqual(DEFAULT_FLOW_TIMEOUTS);
  });

  it("maps customer screens to one flow timer and resets on start", () => {
    expect(flowForScreen("home")).toBeNull();
    expect(flowForScreen("phone")).toBe("activation");
    expect(flowForScreen("swipe")).toBe("wristband");
    expect(flowForScreen("info-result")).toBe("playerInfo");
  });

  it("ticks once per second and times out exactly once", () => {
    vi.useFakeTimers();
    const ticks: number[] = [];
    const expired: string[] = [];
    const controller = createFlowTimeoutController({
      getTimeout: () => 10,
      onTick: (remaining) => ticks.push(remaining),
      onTimeout: (flow) => expired.push(flow),
    });
    controller.start("activation");
    vi.advanceTimersByTime(10000);
    expect(ticks).toEqual([10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]);
    expect(expired).toEqual(["activation"]);
    vi.advanceTimersByTime(5000);
    expect(expired).toEqual(["activation"]);
    vi.useRealTimers();
  });
});
