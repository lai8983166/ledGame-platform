import { describe, expect, it } from "vitest";
import { canClearWristbandBalance, canReclaimWristband, normalizeWristbandUid } from "./wristbandActions";

describe("wristband balance actions", () => {
  it("normalizes the real reader UID without inventing an identifier", () => {
    expect(normalizeWristbandUid(" 2283055618\n")).toBe("2283055618");
    expect(normalizeWristbandUid("UID: 2283055618")).toBe("2283055618");
    expect(normalizeWristbandUid("no-card")).toBe("");
  });

  it("allows clearing only an unbound charged wristband balance", () => {
    expect(canClearWristbandBalance("charged")).toBe(true);
    expect(canClearWristbandBalance("ready")).toBe(false);
    expect(canClearWristbandBalance("active")).toBe(false);
    expect(canClearWristbandBalance("expired")).toBe(false);
  });

  it("allows reclaiming only an expired wristband", () => {
    expect(canReclaimWristband("expired")).toBe(true);
    expect(canReclaimWristband("charged")).toBe(false);
    expect(canReclaimWristband("ready")).toBe(false);
    expect(canReclaimWristband("active")).toBe(false);
  });
});
