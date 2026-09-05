import { describe, expect, it } from "vitest";
import { resolveAgentConfig } from "../src/config.js";
import { buildPlan } from "../src/plan.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type ConnectionInfo } from "../src/types.js";

const connection: ConnectionInfo = {
  formatVersion: FORMAT_VERSION,
  runId: "CONC-20260904-001",
  platformBaseUrl: "http://127.0.0.1:18090",
  testPort: 18090,
  centerLogPath: "C:/runs/server.log",
  runRoot: "C:/runs/run",
  generatedAt: "2026-09-04T00:00:00.000Z",
  safetyConfirmation: SAFETY_CONFIRMATION,
};

function config(agentId: string) {
  return resolveAgentConfig(connection, {
    agentId,
    profile: "load",
    registrationWorkers: 20,
    gameWorkers: 20,
    iterationsPerWorker: 100,
    safetyConfirmation: SAFETY_CONFIRMATION,
  });
}

describe("deterministic test identities", () => {
  it("are stable for the same input", () => {
    const first = buildPlan(config("B"), new Date(0));
    const second = buildPlan(config("B"), new Date(0));
    expect(second).toEqual(first);
  });

  it("do not collide across two large agent plans", () => {
    const planB = buildPlan(config("B"));
    const planC = buildPlan(config("C"));
    const items = [...planB.items, ...planC.items];
    expect(new Set(items.map((item) => item.operationId)).size).toBe(items.length);
    expect(new Set(items.map((item) => item.phone)).size).toBe(items.length);
    expect(new Set(items.map((item) => item.uid)).size).toBe(items.length);
    const sessions = items.filter((item) => item.flowType === "game").map((item) => item.externalSessionId);
    expect(new Set(sessions).size).toBe(sessions.length);
    const devicesB = new Set(planB.items.filter((item) => item.flowType === "game").map((item) => item.deviceId));
    const devicesC = new Set(planC.items.filter((item) => item.flowType === "game").map((item) => item.deviceId));
    expect([...devicesB].some((device) => devicesC.has(device))).toBe(false);
    expect(items.every((item) => /^\d{15}$/.test(item.phone))).toBe(true);
    expect(items.every((item) => /^\d{20}$/.test(item.uid))).toBe(true);
  });
});
