import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { agentRunPaths } from "../src/agent.js";
import { readRemoteAgentLocalConfig, writeRemoteAgentLocalConfig } from "../src/agent-local-config.js";
import { startControllerServer, type RunningController } from "../src/controller.js";
import { runRemoteAgent, waitForCurrentRun } from "../src/remote-agent.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type AgentConfig, type AgentSummary, type ConnectionInfo, type ControllerConfig, type RemoteAgentConfig, type VerificationReport } from "../src/types.js";

const roots: string[] = [];
const controllers: RunningController[] = [];

function remoteConfig(root: string, agentId: "B" | "C", controllerUrl: string): RemoteAgentConfig {
  return { controllerUrl, agentId, outputRoot: path.join(root, `agent-${agentId}`), waitTimeoutMs: 10_000 };
}

function summary(config: AgentConfig): AgentSummary {
  const now = new Date().toISOString();
  return {
    formatVersion: FORMAT_VERSION,
    runId: config.runId,
    agentId: config.agentId,
    profile: config.profile,
    platformBaseUrl: config.platformBaseUrl,
    startedAt: now,
    endedAt: now,
    planned: 0,
    attempted: 0,
    succeeded: 0,
    failed: 0,
    incomplete: 0,
    http5xx: 0,
    timeouts: 0,
    networkErrors: 0,
    durationSamplesMs: [],
  };
}

function report(runId: string): VerificationReport {
  return {
    formatVersion: FORMAT_VERSION,
    runId,
    generatedAt: new Date().toISOString(),
    conclusion: "PASSED",
    dataIntegrityPassed: true,
    agents: [],
    overlapSeconds: 1,
    counts: { planned: 0, attempted: 0, succeeded: 0, failed: 0, incomplete: 0, uncertainButCommitted: 0 },
    performance: { requests: 0, requestsPerSecond: 0, p50Ms: 0, p95Ms: 0, p99Ms: 0, warning: false },
    sqliteLockErrors: [], differences: [], coverageBoundary: [], dataDirectories: [],
    flowCounts: { registration: { planned: 0, attempted: 0, succeeded: 0, failed: 0 }, game: { planned: 0, attempted: 0, succeeded: 0, failed: 0 } },
  };
}

afterEach(async () => {
  await Promise.all(controllers.splice(0).map(item => item.close()));
  await Promise.all(roots.splice(0).map(root => fs.rm(root, { recursive: true, force: true })));
});

describe("remote multipoint agent", () => {
  it("persists only the A address and B/C identity for later no-argument reuse", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-agent-config-"));
    roots.push(root);
    const file = path.join(root, "config", "agent-local.json");
    const written = await writeRemoteAgentLocalConfig(file, {
      controllerUrl: "http://192.168.50.10:18091",
      agentId: "b",
      outputRoot: "runs",
      waitTimeoutMs: 300_000,
    }, root);
    const loaded = await readRemoteAgentLocalConfig(file, root);
    expect(loaded).toEqual(written);
    expect(JSON.parse(await fs.readFile(file, "utf8"))).toEqual({
      controllerUrl: "http://192.168.50.10:18091",
      agentId: "B",
      outputRoot: "runs",
      waitTimeoutMs: 300_000,
    });
  });

  it("waits through a temporarily unreachable A machine without starting a workload", async () => {
    let time = 0;
    let calls = 0;
    const run = await waitForCurrentRun({
      controllerUrl: "http://127.0.0.1:18091",
      agentId: "B",
      outputRoot: "runs",
      waitTimeoutMs: 10_000,
    }, {
      now: () => new Date(time),
      sleep: async milliseconds => { time += milliseconds; },
      fetchImpl: vi.fn(async () => {
        calls += 1;
        if (calls < 3) throw new TypeError("fetch failed");
        return new Response(JSON.stringify({ formatVersion: FORMAT_VERSION, runId: "CONC-20260907-001", profile: "quick", platformBaseUrl: "http://192.168.50.10:18090", phase: "WAITING", startedAt: new Date(0).toISOString() }), { status: 200, headers: { "content-type": "application/json" } });
      }),
    });
    expect(calls).toBe(3);
    expect(run).toMatchObject({ runId: "CONC-20260907-001", profile: "quick" });
  });

  it("rejects an invalid current-run response immediately instead of guessing parameters", async () => {
    let sleeps = 0;
    await expect(waitForCurrentRun({
      controllerUrl: "http://127.0.0.1:18091",
      agentId: "B",
      outputRoot: "runs",
      waitTimeoutMs: 10_000,
    }, {
      sleep: async () => { sleeps += 1; },
      fetchImpl: async () => new Response(JSON.stringify({ runId: "bad id", profile: "quick" }), { status: 200, headers: { "content-type": "application/json" } }),
    })).rejects.toThrow("批次阶段无效");
    expect(sleeps).toBe(0);
  });

  it("runs two joined agents, keeps local evidence, uploads it and triggers automatic verification", async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-remote-e2e-"));
    roots.push(root);
    const runId = "CONC-20260907-002";
    const runRoot = path.join(root, runId);
    await fs.mkdir(runRoot, { recursive: true });
    const connection: ConnectionInfo = {
      formatVersion: FORMAT_VERSION, runId, platformBaseUrl: "http://127.0.0.1:18090", testPort: 18090,
      centerLogPath: path.join(runRoot, "server.log"), runRoot, generatedAt: new Date().toISOString(), safetyConfirmation: SAFETY_CONFIRMATION,
    };
    await fs.writeFile(path.join(runRoot, "connection.json"), JSON.stringify(connection));
    const config: ControllerConfig = {
      profile: "quick", controlPort: 0, startDelayMs: 1, offlineAfterMs: 10_000, maxArtifactBytes: 1024 * 1024,
      center: { runId, memberAdminExecutable: "unused.exe", testRoot: root, lanHost: "127.0.0.1", testPort: 18090, normalPlatformUrl: "http://127.0.0.1:8090", startupTimeoutMs: 1000, safetyConfirmation: SAFETY_CONFIRMATION },
    };
    const verify = vi.fn(async () => report(runId));
    const controller = await startControllerServer(config, connection, {
      verify,
      writeReport: async (_value, outputDirectory) => {
        await fs.mkdir(outputDirectory, { recursive: true });
        const markdown = path.join(outputDirectory, "验收报告.md");
        const json = path.join(outputDirectory, "验收报告.json");
        await fs.writeFile(markdown, "通过");
        await fs.writeFile(json, "{}");
        return { markdown, json };
      },
      openReport: () => undefined,
    }, "127.0.0.1");
    controllers.push(controller);

    const executed: AgentConfig[] = [];
    const fakeRun = async (agentConfig: AgentConfig): Promise<AgentSummary> => {
      executed.push(agentConfig);
      const paths = agentRunPaths(agentConfig);
      await fs.mkdir(paths.directory, { recursive: true });
      await fs.writeFile(paths.plan, JSON.stringify({ runId, agentId: agentConfig.agentId, items: [] }));
      await fs.writeFile(paths.results, "");
      const value = summary(agentConfig);
      await fs.writeFile(paths.summary, JSON.stringify(value));
      return value;
    };
    const dependencies = { run: fakeRun as typeof import("../src/agent.js").runAgent, sleep: async (milliseconds: number) => { await new Promise(resolve => setTimeout(resolve, Math.min(milliseconds, 5))); } };
    const [b, c] = await Promise.all([
      runRemoteAgent(remoteConfig(root, "B", controller.baseUrl), dependencies),
      runRemoteAgent(remoteConfig(root, "C", controller.baseUrl), dependencies),
    ]);
    expect([b.agentId, c.agentId].sort()).toEqual(["B", "C"]);
    expect(executed.map(item => [item.runId, item.profile, item.platformBaseUrl])).toEqual([
      [runId, "quick", connection.platformBaseUrl], [runId, "quick", connection.platformBaseUrl],
    ]);
    for (const id of ["B", "C"] as const) {
      expect(await fs.stat(path.join(root, `agent-${id}`, runId, id, "summary.json"))).toBeTruthy();
      expect(await fs.stat(path.join(runRoot, id, "summary.json"))).toBeTruthy();
    }
    for (let attempt = 0; attempt < 50 && controller.snapshot().run.phase !== "COMPLETE"; attempt += 1) await new Promise(resolve => setTimeout(resolve, 10));
    expect(controller.snapshot()).toMatchObject({ run: { phase: "COMPLETE" }, report: { conclusion: "PASSED" } });
    expect(verify).toHaveBeenCalledTimes(1);
  });
});
