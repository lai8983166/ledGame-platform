import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { classifyFinalState, loadAgentArtifacts, percentile, renderChineseMarkdown, responseWasUncertain, scanCenterLog, validateArtifacts, verifyGame, verifyGlobalGameRecord, verifyMember, verifyWristband, type AgentArtifacts } from "../src/verifier.js";
import { FORMAT_VERSION, type AgentSummary, type Difference, type GamePlanItem, type PlanFile, type VerificationReport } from "../src/types.js";

function artifact(agentId: string, start = "2026-09-04T08:00:00.000Z", end = "2026-09-04T08:01:00.000Z"): AgentArtifacts {
  const plan: PlanFile = { formatVersion: FORMAT_VERSION, runId: "CONC-TEST", agentId, profile: "smoke", platformBaseUrl: "http://127.0.0.1:18090", generatedAt: start, items: [{ operationId: `op-${agentId}`, flowType: "registration", worker: 1, iteration: 1, phone: agentId === "B" ? "900000000000001" : "900000000000002", uid: agentId === "B" ? "80000000000000000001" : "80000000000000000002", memberName: `并发测试-${agentId}`, durationMinutes: 30 }] };
  const summary: AgentSummary = { formatVersion: FORMAT_VERSION, runId: plan.runId, agentId, profile: "smoke", platformBaseUrl: plan.platformBaseUrl, startedAt: start, endedAt: end, planned: 1, attempted: 1, succeeded: 1, failed: 0, incomplete: 0, http5xx: 0, timeouts: 0, networkErrors: 0, durationSamplesMs: [10] };
  return { directory: agentId, plan, summary, results: [{ formatVersion: FORMAT_VERSION, operationId: `op-${agentId}`, flowType: "registration", startedAt: start, endedAt: end, success: true, steps: [] }] };
}

const temporaryRoots: string[] = [];
afterEach(async () => Promise.all(temporaryRoots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true }))));

describe("verification input and report", () => {
  it("accepts two distinct, overlapping agents", () => expect(validateArtifacts("CONC-TEST", [artifact("B"), artifact("C")])).toEqual([]));
  it("reports a persisted plan item without a result as incomplete", () => {
    const first = artifact("B"); first.results = [];
    expect(validateArtifacts("CONC-TEST", [first, artifact("C")]).map((item) => item.code)).toContain("PLAN_INCOMPLETE");
  });
  it("rejects a missing agent result directory before reconciliation", async () => {
    await expect(loadAgentArtifacts(path.join(os.tmpdir(), "definitely-missing-ledgame-agent"))).rejects.toThrow();
  });
  it("rejects duplicate agents, collisions, mismatched run and non-overlap", () => {
    const first = artifact("B", "2026-09-04T08:00:00Z", "2026-09-04T08:01:00Z");
    const second = artifact("B", "2026-09-04T09:00:00Z", "2026-09-04T09:01:00Z");
    second.plan.runId = "OTHER";
    const codes = validateArtifacts("CONC-TEST", [first, second]).map((item) => item.code);
    expect(codes).toEqual(expect.arrayContaining(["RUN_ID_MISMATCH", "AGENT_ID_DUPLICATE", "IDENTITY_COLLISION", "NO_RUNTIME_OVERLAP"]));
  });
  it("renders a Chinese, field-oriented report", () => {
    const report = { formatVersion: 1, runId: "CONC-TEST", generatedAt: "now", conclusion: "FAILED", dataIntegrityPassed: false, agents: [], overlapSeconds: 5, counts: { planned: 2, attempted: 2, succeeded: 1, failed: 1, incomplete: 0, uncertainButCommitted: 1 }, performance: { requests: 3, requestsPerSecond: 1.5, p50Ms: 10, p95Ms: 20, p99Ms: 20, warning: false }, sqliteLockErrors: [], differences: [{ code: "POINTS_MISMATCH", message: "积分错误" }], coverageBoundary: ["不覆盖 UI"], dataDirectories: ["runs/CONC-TEST"], flowCounts: { registration: { planned: 1, attempted: 1, succeeded: 1, failed: 0 }, game: { planned: 1, attempted: 1, succeeded: 0, failed: 1 } } } satisfies VerificationReport;
    expect(renderChineseMarkdown(report)).toMatchSnapshot();
  });
  it("separates uncertain response loss from persisted data loss", () => {
    const base = artifact("B").results[0]!;
    const uncertainResult = { ...base, success: false, steps: [{ name: "settle", method: "POST", path: "/api/game-plays/1/settle", kind: "timeout" as const, startedAt: base.startedAt, endedAt: base.endedAt, durationMs: 1000 }] };
    const rejectedResult = { ...base, success: false, steps: [{ name: "charge", method: "POST", path: "/api/wristbands/charge", kind: "http" as const, startedAt: base.startedAt, endedAt: base.endedAt, durationMs: 10, status: 500 }] };
    expect(responseWasUncertain(uncertainResult)).toBe(true);
    expect(responseWasUncertain(rejectedResult)).toBe(false);
    expect(classifyFinalState(uncertainResult, 2, 2)).toBe("uncertain-committed");
    expect(classifyFinalState(uncertainResult, 2, 3)).toBe("failed");
    expect(classifyFinalState(rejectedResult, 2, 2)).toBe("failed");
    expect(classifyFinalState(undefined, 2, 2)).toBe("unattempted");
  });
  it("classifies only relevant center log evidence and calculates percentiles", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-log-scan-")); temporaryRoots.push(root);
    const log = path.join(root, "server.log");
    await fs.writeFile(log, "ordinary info\nSQLITE_BUSY write failed\nHTTP 503\ndatabase is locked\n", "utf8");
    expect(await scanCenterLog(log)).toHaveLength(3);
    expect(percentile([1, 2, 3, 100], .5)).toBe(2);
    expect(percentile([1, 2, 3, 100], .95)).toBe(100);
  });

  it.each([
    ["会员缺失", () => { const differences: Difference[] = []; verifyMember(gameItem, [], differences, "B"); return differences; }, "MEMBER_COUNT_MISMATCH"],
    ["会员名称错误", () => { const differences: Difference[] = []; verifyMember(gameItem, [{ id: 1, phone: gameItem.phone, name: "wrong" }], differences, "B"); return differences; }, "MEMBER_NAME_MISMATCH"],
    ["手环错绑", () => { const differences: Difference[] = []; verifyWristband(gameItem, { uid: gameItem.uid, status: "ACTIVE", durationMinutes: 30, memberId: 2 }, 1, differences, "B"); return differences; }, "WRISTBAND_BINDING_MISMATCH"],
    ["游戏缺失", () => { const differences: Difference[] = []; verifyGame(gameItem, { points: { total: 50 }, recentPlays: [] }, differences, "B"); return differences; }, "GAME_COUNT_MISMATCH"],
    ["重复游戏", () => { const play = { status: "COMPLETED", rawScore: 50, pointsAwarded: 50, gameId: "concurrency-test-game", deviceId: gameItem.deviceId }; const differences: Difference[] = []; verifyGame(gameItem, { points: { total: 100 }, recentPlays: [play, play] }, differences, "B"); return differences; }, "GAME_COUNT_MISMATCH"],
    ["游戏未结算", () => { const differences: Difference[] = []; verifyGame(gameItem, { points: { total: 0 }, recentPlays: [{ status: "RUNNING", rawScore: null, pointsAwarded: 0, gameId: "concurrency-test-game", deviceId: gameItem.deviceId }] }, differences, "B"); return differences; }, "GAME_STATUS_MISMATCH"],
    ["积分错误", () => { const differences: Difference[] = []; verifyGame(gameItem, { points: { total: 1 }, recentPlays: [{ status: "COMPLETED", rawScore: 50, pointsAwarded: 1, gameId: "concurrency-test-game", deviceId: gameItem.deviceId }] }, differences, "B"); return differences; }, "POINTS_MISMATCH"],
    ["外部会话重复", () => { const play = { externalSessionId: gameItem.externalSessionId, deviceId: gameItem.deviceId }; const differences: Difference[] = []; verifyGlobalGameRecord(gameItem, 1, [play, play], differences, "B"); return differences; }, "GAME_SESSION_COUNT_MISMATCH"],
    ["游戏关联错误", () => { const differences: Difference[] = []; verifyGlobalGameRecord(gameItem, 1, [{ externalSessionId: gameItem.externalSessionId, deviceId: gameItem.deviceId, memberId: 2, uid: "wrong", roomId: "wrong" }], differences, "B"); return differences; }, "GAME_MEMBER_MISMATCH"],
  ])("detects %s", (_label, evaluate, expectedCode) => {
    const value = evaluate();
    const differences = Array.isArray(value) ? value : [];
    expect(differences.map((item) => item.code)).toContain(expectedCode);
  });
});

const gameItem: GamePlanItem = { operationId: "op-game", flowType: "game", worker: 1, iteration: 1, phone: "900000000000003", uid: "80000000000000000003", memberName: "并发测试-G", durationMinutes: 30, deviceId: "device-g", roomId: "room-g", externalSessionId: "session-g", rawScore: 50 };
