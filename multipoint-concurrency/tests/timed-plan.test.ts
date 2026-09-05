import { describe, expect, it } from "vitest";
import { resolveAgentConfig } from "../src/config.js";
import { buildPlan } from "../src/plan.js";
import { expectedTotals, renderPreview } from "../src/timed-plan.js";
import { SAFETY_CONFIRMATION, type ConnectionInfo } from "../src/types.js";

const connection = { runId: "TIMED-TEST-001", platformBaseUrl: "http://127.0.0.1:18090" } as ConnectionInfo;
export function timedConfig(profile = "quick", agentId = "B") {
  return resolveAgentConfig(connection, { profile, agentId, safetyConfirmation: SAFETY_CONFIRMATION });
}

describe("定时随机门店计划", () => {
  it.each([["quick", 300], ["standard", 1800], ["soak", 7200], ["overnight", 43200]])("%s 固定时长、设备数量及完整预期", (profile, seconds) => {
    const plan = buildPlan(timedConfig(String(profile)), new Date(0));
    expect(plan.schedule).toMatchObject({ durationMs: Number(seconds) * 1000, registrationWorkers: 3, gameWorkers: 6 });
    expect(new Set(plan.items.filter(i => i.flowType === "registration").map(i => i.worker)).size).toBe(3);
    expect(new Set(plan.items.filter(i => i.flowType === "game").map(i => i.worker)).size).toBe(6);
    expect(plan.items.every(i => i.scheduledSteps!.every(s => s.atMs < Number(seconds) * 1000))).toBe(true);
    expect(plan.expected).toEqual(expectedTotals(plan.items));
    expect(plan.items.some(i => i.memberMode === "existing")).toBe(true);
    expect(plan.items.some(i => i.memberMode === "replay")).toBe(true);
    expect(plan.items.filter(i => i.flowType === "game").every(i => i.playDurationMs! >= 30000 && i.playDurationMs! <= 180000)).toBe(true);
    expect(new Set(plan.items.map(i => i.scheduledSteps![0]!.atMs)).size).toBeGreaterThan(10);
  });

  it("同一输入可复现，两机身份隔离，复用仅指向本设备先前计划", () => {
    const b = buildPlan(timedConfig("overnight"), new Date(0));
    expect(buildPlan(timedConfig("overnight"), new Date(0))).toEqual(b);
    const c = buildPlan(timedConfig("overnight", "C"));
    const phones = new Set(b.items.map(i => i.phone));
    expect(c.items.every(i => !phones.has(i.phone))).toBe(true);
    for (const item of b.items.filter(i => i.dependsOn)) {
      const source = b.items.find(i => i.operationId === item.dependsOn)!;
      expect(source.worker).toBe(item.worker);
      expect(source.iteration).toBeLessThan(item.iteration);
      expect(item.phone).toBe(source.phone);
      expect(item.uid === source.uid).toBe(item.memberMode === "replay");
    }
    const preview = renderPreview([b, c]);
    expect(preview).toContain(String(expectedTotals([...b.items, ...c.items]).points));
    expect(preview).toContain("720 分钟");
    for (const key of ["operationId", "uid", "externalSessionId"] as const) {
      const uniqueItems = [...b.items, ...c.items].filter(i => key === "externalSessionId" ? i.flowType === "game" : key !== "uid" || i.memberMode !== "replay");
      const values = uniqueItems.map(i => key === "externalSessionId" ? (i.flowType === "game" ? i.externalSessionId : "") : i[key]);
      expect(new Set(values).size).toBe(values.length);
    }
  });

  it("拒绝覆盖定时档位的内部负载参数", () => {
    expect(() => resolveAgentConfig(connection, { profile: "quick", agentId: "B", registrationWorkers: 1, safetyConfirmation: SAFETY_CONFIRMATION })).toThrow("固定");
  });
});
