import path from "node:path";
import { describe, expect, it } from "vitest";
import { generateRunId, resolveAgentConfig, resolveCenterConfig } from "../src/config.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type ConnectionInfo } from "../src/types.js";

const connection: ConnectionInfo = {
  formatVersion: FORMAT_VERSION,
  runId: "CONC-20260904-001",
  platformBaseUrl: "http://192.168.124.10:18090",
  testPort: 18090,
  centerLogPath: "C:/runs/server.log",
  runRoot: "C:/runs/CONC-20260904-001",
  generatedAt: "2026-09-04T00:00:00.000Z",
  safetyConfirmation: SAFETY_CONFIRMATION,
};

describe("multipoint concurrency configuration", () => {
  it("resolves a safe isolated center configuration", () => {
    const config = resolveCenterConfig({
      runId: "CONC-20260904-001",
      memberAdminExecutable: "release/member.exe",
      testRoot: "test-runs",
      lanHost: "192.168.124.10",
      safetyConfirmation: SAFETY_CONFIRMATION,
    }, "C:/project");
    expect(config.testPort).toBe(18090);
    expect(config.memberAdminExecutable).toBe(path.resolve("C:/project", "release/member.exe"));
    expect(config.testRoot).toBe(path.resolve("C:/project", "test-runs"));
  });

  it.each([
    [{ runId: "bad id" }, "runId"],
    [{ lanHost: "http://host" }, "lanHost"],
    [{ testPort: 80 }, "testPort"],
    [{ safetyConfirmation: "yes" }, "安全确认"],
  ])("rejects unsafe center input %#", (override, message) => {
    expect(() => resolveCenterConfig({
      runId: "CONC-20260904-001",
      memberAdminExecutable: "member.exe",
      lanHost: "192.168.1.2",
      safetyConfirmation: SAFETY_CONFIRMATION,
      ...override,
    })).toThrow(message);
  });

  it("loads smoke defaults and validates an agent before writes", () => {
    const config = resolveAgentConfig(connection, {
      agentId: "B",
      profile: "smoke",
      safetyConfirmation: SAFETY_CONFIRMATION,
    }, "C:/agent");
    expect(config).toMatchObject({ registrationWorkers: 1, gameWorkers: 1, iterationsPerWorker: 2 });
    expect(config.outputRoot).toBe(path.resolve("C:/agent", "runs"));
  });

  it.each([
    [{ agentId: "B C" }, "agentId"],
    [{ profile: "huge" }, "profile"],
    [{ registrationWorkers: -1 }, "registrationWorkers"],
    [{ gameWorkers: 0, registrationWorkers: 0 }, "至少需要"],
    [{ requestTimeoutMs: 10 }, "requestTimeoutMs"],
    [{ safetyConfirmation: "" }, "安全确认"],
  ])("rejects invalid agent input %#", (override, message) => {
    expect(() => resolveAgentConfig(connection, {
      agentId: "B",
      profile: "smoke",
      safetyConfirmation: SAFETY_CONFIRMATION,
      ...override,
    })).toThrow(message);
  });

  it("generates a stable timestamp run id", () => {
    expect(generateRunId(new Date("2026-09-04T12:34:56.000Z"))).toBe("CONC-20260904123456");
  });
});
