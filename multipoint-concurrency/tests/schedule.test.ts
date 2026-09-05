import { afterEach, describe, expect, it, vi } from "vitest";
import { executeFlow } from "../src/agent.js";
import { runScheduled } from "../src/schedule.js";
import type { FlowResult, PlanFile, StepResult } from "../src/types.js";

afterEach(() => vi.useRealTimers());
const clock = { now: () => Date.now(), sleep: (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms)) };
function plan(): PlanFile {
  return { formatVersion: 1, runId: "SCHEDULE-TEST", agentId: "B", profile: "quick", platformBaseUrl: "http://localhost:1", generatedAt: "", items: [1, 2].map(i => ({
    operationId: `op${i}`, flowType: "registration", worker: 1, iteration: i, phone: `999999${i}`, uid: `88${i}`, memberName: "测试", durationMinutes: 30,
    scheduledSteps: [{ name: "charge", atMs: i * 100 }],
  })), schedule: { durationMs: 2000, graceMs: 1000, lagThresholdMs: 100, seed: "test", registrationWorkers: 1, gameWorkers: 0, queries: [] } };
}
function response(): StepResult { return { name: "charge", method: "POST", path: "/x", kind: "http", status: 200, startedAt: "", endedAt: "", durationMs: 600 }; }

describe("单调时间调度与积压", () => {
  it("慢请求保留原定时间并检测积压，不移动后续计划", async () => {
    vi.useFakeTimers(); vi.setSystemTime(0);
    const saved: FlowResult[] = [];
    const promise = runScheduled(plan(), { step: async () => { await clock.sleep(600); return response(); } }, async (item, client) => ({
      formatVersion: 1, operationId: item.operationId, flowType: item.flowType, startedAt: "", endedAt: "", success: true,
      steps: [await client.step("charge", "POST", "/x")],
    }), async r => { saved.push(r); }, undefined, clock);
    await vi.runAllTimersAsync();
    const { metrics } = await promise;
    expect(saved[1]!.steps[0]).toMatchObject({ plannedOffsetMs: 200, actualOffsetMs: 700, startDelayMs: 500 });
    expect(metrics.maxPending).toBeGreaterThanOrEqual(1);
    expect(metrics.executionPassed).toBe(false);
    expect(metrics.actualDurationMs).toBeGreaterThanOrEqual(2000);
  });

  it("超过收尾截止不再发出后续步骤，并保留未完成计数", async () => {
    vi.useFakeTimers(); vi.setSystemTime(0);
    const input = plan(); input.schedule!.durationMs = 250; input.schedule!.graceMs = 100;
    let requests = 0;
    const p = runScheduled(input, { step: async () => { requests++; await clock.sleep(400); return response(); } }, async (item, client) => ({
      formatVersion: 1, operationId: item.operationId, flowType: item.flowType, startedAt: "", endedAt: "", success: true,
      steps: [await client.step("charge", "POST", "/x")],
    }), async () => {}, undefined, clock);
    await vi.runAllTimersAsync();
    const result = await p;
    expect(requests).toBe(1);
    expect(result.metrics.pendingAtDeadline).toBe(1);
    expect(result.metrics.executionPassed).toBe(false);
  });

  it("业务失败不会伪装为成功或无穷等待", async () => {
    vi.useFakeTimers(); vi.setSystemTime(0);
    const input = plan();
    input.items[1]!.dependsOn = "op1";
    const saved: FlowResult[] = [];
    const p = runScheduled(input, { step: async () => ({ ...response(), status: 503 }) }, executeFlow, async r => { saved.push(r); }, undefined, clock);
    await vi.runAllTimersAsync();
    const result = await p;
    expect(saved).toHaveLength(2);
    expect(saved.every(r => !r.success)).toBe(true);
    expect(saved[1]!.error).toContain("受阻");
    expect(result.metrics.skippedSteps).toBe(1);
  });

  it("实际开局后仍等待完整游玩时间，主动等待不等同请求耗时", async () => {
    vi.useFakeTimers(); vi.setSystemTime(0);
    const input = plan(); input.items = [{ ...input.items[0]!, flowType: "game", memberMode: "replay",
      deviceId: "g1", roomId: "r1", externalSessionId: "s1", rawScore: 100, playDurationMs: 1000,
      scheduledSteps: ["memberLookup", "activate", "startGame", "settleGame", "playerInfo"].map((name, i) => ({ name, atMs: i < 3 ? i * 10 : 1020 + (i - 3) * 10 })) }];
    const calls: Array<{name: string; at: number}> = [];
    const p = runScheduled(input, { step: async name => {
      calls.push({ name, at: Date.now() });
      await clock.sleep(30);
      return { ...response(), name, response: name === "memberLookup" ? [{ id: 1, phone: input.items[0]!.phone }] : { id: 1 } };
    } }, executeFlow, async () => {}, undefined, clock);
    await vi.runAllTimersAsync(); await p;
    expect(calls.find(c => c.name === "settleGame")!.at - calls.find(c => c.name === "startGame")!.at).toBeGreaterThanOrEqual(1030);
    expect(calls.map(c => c.name)).not.toContain("charge");
    expect(calls.map(c => c.name)).not.toContain("memberCreate");
  });
});
