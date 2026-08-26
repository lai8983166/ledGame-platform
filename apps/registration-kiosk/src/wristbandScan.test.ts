import { describe, expect, it } from "vitest";
import {
  cancelWristbandScan,
  completeWristbandScan,
  consumeWristbandScanKey,
  createWristbandScanSession,
  failWristbandScan,
  startWristbandScan,
} from "./wristbandScan";

describe("explicit wristband scan session", () => {
  it("ignores keyboard input until the customer starts scanning", () => {
    const session = createWristbandScanSession();
    expect(consumeWristbandScanKey(session, "2")).toBeNull();
    expect(consumeWristbandScanKey(session, "Enter")).toBeNull();
    expect(session.buffer).toBe("");
  });

  it("collects digits and submits a UID only when Enter ends the reader frame", () => {
    const session = createWristbandScanSession();
    startWristbandScan(session);
    "2283055618".split("").forEach((key) => consumeWristbandScanKey(session, key));
    const frame = consumeWristbandScanKey(session, "Enter");
    expect(frame).toEqual({ uid: "2283055618", revision: session.revision });
    expect(session.state).toBe("submitting");
    expect(session.buffer).toBe("");
  });

  it("cancels without success and rejects a late response from the cancelled request", () => {
    const session = createWristbandScanSession();
    startWristbandScan(session);
    "2283055618".split("").forEach((key) => consumeWristbandScanKey(session, key));
    const frame = consumeWristbandScanKey(session, "Enter")!;
    cancelWristbandScan(session);
    expect(session).toMatchObject({ state: "idle", buffer: "" });
    expect(completeWristbandScan(session, frame.revision)).toBe(false);
    expect(session.state).toBe("idle");
  });

  it("clears a failed frame and remains ready for a retry", () => {
    const session = createWristbandScanSession();
    startWristbandScan(session);
    consumeWristbandScanKey(session, "1");
    const frame = consumeWristbandScanKey(session, "Enter")!;
    expect(failWristbandScan(session, frame.revision)).toBe(true);
    expect(session).toMatchObject({ state: "waiting", buffer: "" });
    expect(consumeWristbandScanKey(session, "2")).toBeNull();
    expect(session.buffer).toBe("2");
  });
});
