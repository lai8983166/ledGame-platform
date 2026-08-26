import { describe, expect, it } from "vitest";
import {
  acceptChargeScan,
  beginChargeSubmit,
  cancelChargeSession,
  consumeChargeScanKey,
  createWristbandChargeSession,
  failChargeSubmit,
  setChargeMinutes,
  startChargeSession,
  succeedChargeSubmit,
} from "./wristbandChargeSession";

describe("wristband charge session", () => {
  it("captures numeric UID and Enter only while scanning", () => {
    const state = createWristbandChargeSession();
    expect(consumeChargeScanKey(state, "2")).toBeNull();
    startChargeSession(state);
    for (const key of "2283055618") consumeChargeScanKey(state, key);
    const token = consumeChargeScanKey(state, "Enter");
    expect(token).toMatchObject({ uid: "2283055618" });
    expect(state.status).toBe("checking");
    expect(consumeChargeScanKey(state, "Enter")).toBeNull();
  });

  it("accepts unknown and IN_STOCK wristbands but retries other statuses", () => {
    for (const status of [null, "IN_STOCK"] as const) {
      const state = createWristbandChargeSession();
      startChargeSession(state);
      for (const key of "2283055618") consumeChargeScanKey(state, key);
      const token = consumeChargeScanKey(state, "Enter")!;
      expect(acceptChargeScan(state, token, status)).toBe(true);
      expect(state).toMatchObject({ status: "details", uid: "2283055618", minutes: 60 });
    }
    const state = createWristbandChargeSession();
    startChargeSession(state);
    for (const key of "2283055618") consumeChargeScanKey(state, key);
    const token = consumeChargeScanKey(state, "Enter")!;
    expect(acceptChargeScan(state, token, "ACTIVE")).toBe(false);
    expect(state).toMatchObject({ status: "scanning", uid: "", buffer: "" });
    expect(state.error).toContain("ACTIVE");
  });

  it("cancels scanning or details and invalidates late checks", () => {
    const state = createWristbandChargeSession();
    startChargeSession(state);
    for (const key of "2283055618") consumeChargeScanKey(state, key);
    const token = consumeChargeScanKey(state, "Enter")!;
    expect(cancelChargeSession(state)).toBe(true);
    expect(acceptChargeScan(state, token, null)).toBe(false);
    expect(state).toMatchObject({ status: "idle", uid: "", buffer: "" });
  });

  it("validates minutes and preserves details after a failed submit", () => {
    const state = createWristbandChargeSession();
    startChargeSession(state);
    for (const key of "2283055618") consumeChargeScanKey(state, key);
    acceptChargeScan(state, consumeChargeScanKey(state, "Enter")!, null);
    setChargeMinutes(state, 0);
    expect(beginChargeSubmit(state)).toBeNull();
    expect(state.error).toContain("1 到 1440");
    setChargeMinutes(state, 90);
    const request = beginChargeSubmit(state)!;
    expect(request).toMatchObject({ uid: "2283055618", minutes: 90 });
    expect(beginChargeSubmit(state)).toBeNull();
    failChargeSubmit(state, request, "本机服务请求失败");
    expect(state).toMatchObject({ status: "details", uid: "2283055618", minutes: 90, error: "本机服务请求失败" });
    const retry = beginChargeSubmit(state)!;
    succeedChargeSubmit(state, retry);
    expect(state).toMatchObject({ status: "idle", uid: "", minutes: 60, error: "" });
  });

  it("does not cancel an in-flight database write", () => {
    const state = createWristbandChargeSession();
    startChargeSession(state);
    for (const key of "2283055618") consumeChargeScanKey(state, key);
    acceptChargeScan(state, consumeChargeScanKey(state, "Enter")!, null);
    expect(beginChargeSubmit(state)).not.toBeNull();
    expect(cancelChargeSession(state)).toBe(false);
    expect(state.status).toBe("submitting");
  });
});
