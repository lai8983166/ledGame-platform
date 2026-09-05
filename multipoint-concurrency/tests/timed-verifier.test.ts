import { describe, expect, it } from "vitest";
import { buildPlan } from "../src/plan.js";
import { resolveAgentConfig } from "../src/config.js";
import { reconcileTimed } from "../src/timed-verifier.js";
import { timedExecutionPassed, validateArtifacts } from "../src/verifier.js";
import type { AgentArtifacts } from "../src/verifier.js";
import { PlatformClient } from "../src/platform-client.js";
import { SAFETY_CONFIRMATION, type AgentSummary, type ConnectionInfo, type Difference, type StepResult } from "../src/types.js";

function fixture(profile = "soak") {
  const artifacts: AgentArtifacts[] = ["B", "C"].map(agentId => {
    const plan = buildPlan(resolveAgentConfig({ runId: "TIMED-VERIFY", platformBaseUrl: "http://localhost:18090" } as ConnectionInfo,
      { profile, agentId, safetyConfirmation: SAFETY_CONFIRMATION }), new Date(0));
    return { directory: agentId, plan, results: plan.items.map(i => ({ formatVersion: 1, operationId: i.operationId, flowType: i.flowType, startedAt: "", endedAt: "", success: true, steps: [] })),
      summary: { runId: plan.runId, agentId, profile: plan.profile, planned: plan.items.length, attempted: plan.items.length,
        startedAt: "2026-09-05T00:00:00Z", endedAt: "2026-09-05T02:00:00Z", schedule: { executionPassed: true } } as AgentSummary };
  });
  const items = artifacts.flatMap(a => a.plan.items);
  const ids = new Map([...new Set(items.map(i => i.phone))].map((phone, i) => [phone, i + 1]));
  const games = items.filter(i => i.flowType === "game").map(i => ({
    memberId: ids.get(i.phone), uid: i.uid, roomId: i.roomId, deviceId: i.deviceId, externalSessionId: i.externalSessionId,
    gameId: "concurrency-test-game", status: "COMPLETED", rawScore: i.rawScore, pointsAwarded: i.rawScore, terminationReason: "NATURAL_COMPLETION",
  }));
  const responseFor = (endpoint: string): unknown => {
    const url = new URL(endpoint, "http://localhost");
    const uid = url.searchParams.get("uid") ?? endpoint.split("/").at(-1);
    const item = items.find(i => i.uid === uid);
    if (url.pathname === "/api/members") {
      const phone = url.searchParams.get("phone"); const member = items.find(i => i.phone === phone)!;
      return [{ id: ids.get(member.phone), phone: member.phone, name: member.memberName,
        pointsTotal: games.filter(g => g.memberId === ids.get(member.phone)).reduce((n, g) => n + g.pointsAwarded, 0) }];
    }
    if (url.pathname === "/api/game-plays") return games.filter(g => g.memberId === Number(url.searchParams.get("memberId")));
    if (!item) throw new Error(endpoint);
    if (url.pathname === "/api/records/wristband-charges") return [{ uid, durationMinutes: item.durationMinutes, amountCents: item.durationMinutes * 100 }];
    if (url.pathname === "/api/records/wristband-bindings") return [{ uid, memberId: ids.get(item.phone) }];
    return { uid, status: item.flowType === "game" ? "ACTIVE" : "READY", memberId: ids.get(item.phone), durationMinutes: item.durationMinutes };
  };
  function client(alter: (endpoint: string, data: unknown) => unknown = (_e, d) => d) {
    const c = new PlatformClient("http://localhost", 1000);
    c.step = async (name, method, endpoint) => ({ name, method, path: endpoint, kind: "http", status: 200, startedAt: "", endedAt: "", durationMs: 1, response: alter(endpoint, responseFor(endpoint)) });
    return c;
  }
  return { artifacts, client, games };
}

describe("长时随机计划核账", () => {
  it("12 小时完整双节点计划仍能核账（离线模拟数据，不代表实际通宵运行）", async () => {
    const f = fixture("overnight");
    const differences: Difference[] = [];
    const result = await reconcileTimed(f.artifacts, f.client(), differences);
    expect(result.actual).toEqual(result.expected);
    expect(differences).toEqual([]);
    expect(result.expected.games).toBeGreaterThan(2000);
  }, 120000);
  it("超过全局 200 局，复用会员与手环仍精确核对预期、实际和积分", async () => {
    const f = fixture(); expect(f.games.length).toBeGreaterThan(200);
    const differences: Difference[] = [];
    const r = await reconcileTimed(f.artifacts, f.client(), differences);
    expect(r.actual).toEqual(r.expected); expect(differences).toEqual([]);
    expect(r.expected.members).toBeLessThan(f.artifacts.flatMap(a => a.plan.items).length);
  });
  it.each(["missing-charge", "duplicate-game", "wrong-points", "wrong-binding"])("%s 不能通过核账", async kind => {
    const f = fixture(); const differences: Difference[] = [];
    let changed = false;
    await reconcileTimed(f.artifacts, f.client((endpoint, data) => {
      if (changed) return data;
      const rows = data as Record<string, unknown>[];
      if (kind === "missing-charge" && endpoint.includes("wristband-charges")) { changed = true; return []; }
      if (kind === "duplicate-game" && endpoint.includes("game-plays") && rows.length) { changed = true; return [...rows, rows[0]]; }
      if (kind === "wrong-points" && endpoint.includes("/members?")) { changed = true; return [{ ...rows[0], pointsTotal: 999999 }]; }
      if (kind === "wrong-binding" && endpoint.includes("wristband-bindings")) { changed = true; return [{ ...rows[0], memberId: -1 }]; }
      return data;
    }), differences);
    expect(changed).toBe(true); expect(differences.length).toBeGreaterThan(0);
  });
  it("不允许删减失败计划后重新计算预期", () => {
    const f = fixture(); f.artifacts[0]!.plan.items.pop();
    expect(validateArtifacts("TIMED-VERIFY", f.artifacts).some(d => d.code === "PLAN_INVALID")).toBe(true);
  });

  it("执行节奏根据逐请求证据复算，不相信摘要中的通过标记", () => {
    const artifact = fixture().artifacts[0]!;
    const schedule = artifact.plan.schedule!;
    const stepResult = (name: string, atMs: number): StepResult => ({ name, method: "GET", path: "/",
      kind: "http", status: 200, startedAt: "", endedAt: "", durationMs: 1,
      plannedOffsetMs: atMs, actualOffsetMs: atMs + 1, startDelayMs: 1 });
    artifact.results = artifact.results.map((r, index) => ({ ...r,
      steps: artifact.plan.items[index]!.scheduledSteps!.map(s => stepResult(s.name, s.atMs)) }));
    artifact.summary.queryResults = schedule.queries.map(q => stepResult(q.name, q.atMs));
    Object.assign(artifact.summary.schedule!, { actualDurationMs: schedule.durationMs, pendingAtDeadline: 0, skippedSteps: 0 });
    expect(timedExecutionPassed(artifact)).toBe(true);
    const query = artifact.summary.queryResults.pop()!;
    expect(timedExecutionPassed(artifact)).toBe(false);
    artifact.summary.queryResults.push(query);
    for (const r of artifact.results) for (const s of r.steps) { s.actualOffsetMs! += 3000; s.startDelayMs! += 3000; }
    expect(artifact.summary.schedule!.executionPassed).toBe(true);
    expect(timedExecutionPassed(artifact)).toBe(false);
  });
});
