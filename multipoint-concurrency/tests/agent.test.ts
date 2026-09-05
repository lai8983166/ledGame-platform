import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { agentRunPaths, executeFlow, readResults, runAgent, type StepClient } from "../src/agent.js";
import { buildPlan } from "../src/plan.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type AgentConfig, type GamePlanItem, type PlanItem, type StepResult } from "../src/types.js";

const roots: string[] = [];
function result(name: string, response: unknown = {}, status = 200): StepResult {
  return { name, method: "POST", path: "/test", kind: "http", status, response, startedAt: new Date().toISOString(), endedAt: new Date().toISOString(), durationMs: 1 };
}
function config(root: string): AgentConfig {
  return { runId: "CONC-20260904-001", agentId: "B", profile: "smoke", platformBaseUrl: "http://127.0.0.1:18090", outputRoot: root, safetyConfirmation: SAFETY_CONFIRMATION, registrationWorkers: 2, gameWorkers: 2, iterationsPerWorker: 2, maxDurationSeconds: 60, requestTimeoutMs: 1000, durationMinutes: 30 };
}
function scriptedClient(failAt?: string): StepClient {
  let memberId = 0;
  let playId = 0;
  return { async step(name) {
    if (name === failAt) return result(name, { error: "controlled" }, 503);
    if (name === "memberLookup") return result(name, []);
    if (name === "memberCreate") return result(name, { id: ++memberId });
    if (name === "startGame") return result(name, { id: ++playId });
    return result(name, name === "playerInfo" ? { points: { total: 1 }, recentPlays: [] } : {});
  } };
}
afterEach(async () => Promise.all(roots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true }))));

describe("agent flows", () => {
  it("executes registration and game API steps in order", async () => {
    const plan = buildPlan(config("."));
    const registration = await executeFlow(plan.items.find((item) => item.flowType === "registration")!, scriptedClient());
    const game = await executeFlow(plan.items.find((item) => item.flowType === "game")!, scriptedClient());
    expect(registration.steps.map((step) => step.name)).toEqual(["charge", "memberLookup", "memberCreate", "bind", "wristbandQuery"]);
    expect(game.steps.map((step) => step.name)).toEqual(["charge", "memberLookup", "memberCreate", "bind", "activate", "startGame", "settleGame", "playerInfo"]);
    expect(game.success).toBe(true);
  });

  it("uses the current member, wristband, activation, start and natural-settlement contracts", async () => {
    const item = buildPlan(config(".")).items.find((candidate) => candidate.flowType === "game") as GamePlanItem;
    const calls: Array<{ name: string; method: string; path: string; body: unknown }> = [];
    const base = scriptedClient();
    await executeFlow(item, { async step(name, method, endpoint, body) {
      calls.push({ name, method, path: endpoint, body });
      return base.step(name, method, endpoint, body);
    } });
    expect(calls).toMatchObject([
      { name: "charge", method: "POST", path: "/api/wristbands/charge", body: { uid: item.uid, durationMinutes: item.durationMinutes } },
      { name: "memberLookup", method: "GET", path: `/api/members?phone=${item.phone}` },
      { name: "memberCreate", method: "POST", path: "/api/members", body: { phone: item.phone, name: item.memberName } },
      { name: "bind", method: "POST", path: "/api/wristbands/bind", body: { uid: item.uid } },
      { name: "activate", method: "POST", path: "/api/game-access/activate", body: { uid: item.uid } },
      { name: "startGame", method: "POST", path: "/api/game-plays/start", body: { uid: item.uid, deviceId: item.deviceId, roomId: item.roomId, externalSessionId: item.externalSessionId } },
      { name: "settleGame", method: "PUT", path: "/api/game-plays/1/result", body: { success: true, terminationReason: "NATURAL_COMPLETED", rawScore: item.rawScore } },
      { name: "playerInfo", method: "GET", path: `/api/player-info?phone=${item.phone}` },
    ]);
  });

  it("skips dependent steps after an earlier failure", async () => {
    const item = buildPlan(config(".")).items[0]!;
    const flow = await executeFlow(item, scriptedClient("charge"));
    expect(flow.success).toBe(false);
    expect(flow.steps.map((step) => step.name)).toEqual(["charge"]);
  });

  it("writes the complete plan before requests and serializes every result", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-agent-")); roots.push(root);
    const value = config(root);
    let planAlreadyWritten = false;
    const client = scriptedClient();
    const inspectingClient: StepClient = { async step(...args) {
      const plan = JSON.parse(await fs.readFile(agentRunPaths(value).plan, "utf8")) as { items: PlanItem[] };
      planAlreadyWritten = plan.items.length === 8;
      return client.step(...args);
    } };
    const summary = await runAgent(value, { client: inspectingClient });
    expect(planAlreadyWritten).toBe(true);
    expect(summary).toMatchObject({ formatVersion: FORMAT_VERSION, planned: 8, attempted: 8, succeeded: 8, failed: 0, incomplete: 0 });
    expect(await readResults(agentRunPaths(value).results)).toHaveLength(8);
    await expect(runAgent(value, { client })).rejects.toThrow("禁止覆盖");
  });

  it("runs different registration/game workers concurrently", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-agent-overlap-")); roots.push(root);
    let active = 0;
    let maximum = 0;
    const base = scriptedClient();
    const client: StepClient = { async step(...args) {
      active++;
      maximum = Math.max(maximum, active);
      await new Promise((resolve) => setTimeout(resolve, 2));
      const value = await base.step(...args);
      active--;
      return value;
    } };
    await runAgent(config(root), { client });
    expect(maximum).toBeGreaterThanOrEqual(4);
  });
});
