import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const viewSource = readFileSync(resolve(fileURLToPath(import.meta.url), "../views/WristbandsView.vue"), "utf8");

describe("member admin wristband operations", () => {
  it("keeps a prominent operator balance-clear action in the management view", () => {
    expect(viewSource).toContain("主动清除手环可用余额");
    expect(viewSource).toContain("clearBalanceFromUid");
    expect(viewSource).toContain("/wristbands/clear");
  });

  it("does not remove the existing bind recovery action", () => {
    expect(viewSource).toContain("/wristbands/unbind");
    expect(viewSource).toContain("解除绑定");
  });
  it("exposes expired wristband reclaim actions", () => {
    expect(viewSource).toContain("/wristbands/reclaim");
    expect(viewSource).toContain("reclaimFromUid");
    expect(viewSource).toContain("回收已到期手环");
  });
});
