import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { startControllerServer, type RunningController } from "../src/controller.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type ConnectionInfo, type ControllerConfig, type VerificationReport } from "../src/types.js";

const roots: string[] = [];
const controllers: RunningController[] = [];

async function fixture(overrides: Partial<ControllerConfig> = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-controller-"));
  roots.push(root);
  const runId = "CONC-20260907-001";
  await fs.mkdir(path.join(root, runId), { recursive: true });
  const connection: ConnectionInfo = {
    formatVersion: FORMAT_VERSION,
    runId,
    platformBaseUrl: "http://127.0.0.1:18090",
    testPort: 18090,
    centerLogPath: path.join(root, runId, "server.log"),
    runRoot: path.join(root, runId),
    generatedAt: new Date().toISOString(),
    safetyConfirmation: SAFETY_CONFIRMATION,
  };
  await fs.writeFile(path.join(connection.runRoot, "connection.json"), JSON.stringify(connection));
  const config: ControllerConfig = {
    profile: "quick",
    controlPort: 0,
    startDelayMs: 1000,
    offlineAfterMs: 10_000,
    maxArtifactBytes: 1024 * 1024,
    center: {
      runId,
      memberAdminExecutable: path.join(root, "member.exe"),
      testRoot: root,
      lanHost: "127.0.0.1",
      testPort: 18090,
      normalPlatformUrl: "http://127.0.0.1:8090",
      startupTimeoutMs: 1000,
      safetyConfirmation: SAFETY_CONFIRMATION,
    },
    ...overrides,
  };
  return { root, connection, config };
}

async function json(response: Response) {
  return await response.json() as Record<string, any>;
}

async function postStatus(baseUrl: string, agentId: string, runId: string, phase = "READY") {
  return fetch(`${baseUrl}/api/agents/${agentId}/status`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ runId, phase, message: `${agentId} ready` }),
  });
}

async function upload(baseUrl: string, agentId: string, runId: string, name: string, body: Buffer, hash = createHash("sha256").update(body).digest("hex")) {
  return fetch(`${baseUrl}/api/agents/${agentId}/artifacts/${encodeURIComponent(name)}`, {
    method: "PUT",
    headers: { "x-run-id": runId, "x-content-sha256": hash, "content-length": String(body.length) },
    body: new Blob([body.toString("utf8")]),
  });
}

function fakeReport(runId: string): VerificationReport {
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
    sqliteLockErrors: [],
    differences: [],
    coverageBoundary: [],
    dataDirectories: [],
    flowCounts: { registration: { planned: 0, attempted: 0, succeeded: 0, failed: 0 }, game: { planned: 0, attempted: 0, succeeded: 0, failed: 0 } },
  };
}

afterEach(async () => {
  await Promise.all(controllers.splice(0).map(controller => controller.close()));
  await Promise.all(roots.splice(0).map(root => fs.rm(root, { recursive: true, force: true })));
});

describe("multipoint lightweight controller", () => {
  it("publishes the current run and releases only after B and C are ready", async () => {
    const { config, connection } = await fixture();
    const controller = await startControllerServer(config, connection, {}, "127.0.0.1");
    controllers.push(controller);
    expect(await json(await fetch(`${controller.baseUrl}/health`))).toMatchObject({ ok: true, runId: connection.runId });
    expect(await json(await fetch(`${controller.baseUrl}/api/run`))).toMatchObject({ runId: connection.runId, profile: "quick", phase: "WAITING" });

    const b = await json(await postStatus(controller.baseUrl, "B", connection.runId));
    expect(b.startAt).toBeUndefined();
    expect(controller.snapshot().run.phase).toBe("WAITING");
    const c = await json(await postStatus(controller.baseUrl, "C", connection.runId));
    expect(c.startAt).toBeTruthy();
    expect(controller.snapshot().run.phase).toBe("RUNNING");
  });

  it("rejects an unknown identity and a stale run without changing the barrier", async () => {
    const { config, connection } = await fixture();
    const controller = await startControllerServer(config, connection, {}, "127.0.0.1");
    controllers.push(controller);
    expect((await postStatus(controller.baseUrl, "A", connection.runId)).status).toBe(400);
    expect((await postStatus(controller.baseUrl, "B", "CONC-OLD-001")).status).toBe(409);
    expect(controller.snapshot().run.phase).toBe("WAITING");
  });

  it("marks a silent running agent as possibly offline without guessing the root cause", async () => {
    const { config, connection } = await fixture();
    let current = new Date("2026-09-07T12:00:00.000Z");
    const controller = await startControllerServer(config, connection, { now: () => current }, "127.0.0.1");
    controllers.push(controller);
    await postStatus(controller.baseUrl, "B", connection.runId, "RUNNING");
    current = new Date("2026-09-07T12:00:11.000Z");
    expect(controller.snapshot().agents.B).toMatchObject({ possiblyOffline: true });
  });

  it("validates, stores and idempotently accepts fixed evidence before one automatic verification", async () => {
    const { config, connection } = await fixture();
    const verify = vi.fn(async () => fakeReport(connection.runId));
    const writeReport = vi.fn(async (_report: VerificationReport, outputDirectory: string) => {
      await fs.mkdir(outputDirectory, { recursive: true });
      const markdown = path.join(outputDirectory, "验收报告.md");
      const jsonFile = path.join(outputDirectory, "验收报告.json");
      await fs.writeFile(markdown, "通过");
      await fs.writeFile(jsonFile, "{}");
      return { markdown, json: jsonFile };
    });
    const controller = await startControllerServer(config, connection, { verify, writeReport, openReport: () => undefined }, "127.0.0.1");
    controllers.push(controller);
    await postStatus(controller.baseUrl, "B", connection.runId);
    await postStatus(controller.baseUrl, "C", connection.runId);

    for (const agentId of ["B", "C"] as const) {
      const plan = Buffer.from(JSON.stringify({ runId: connection.runId, agentId, items: [] }));
      const results = Buffer.from("");
      const summary = Buffer.from(JSON.stringify({ runId: connection.runId, agentId }));
      for (const [name, body] of [["plan.json", plan], ["results.jsonl", results], ["summary.json", summary]] as const) {
        expect((await upload(controller.baseUrl, agentId, connection.runId, name, body)).status).toBe(200);
      }
      const retry = await json(await upload(controller.baseUrl, agentId, connection.runId, "plan.json", plan));
      expect(retry.idempotent).toBe(true);
    }

    const conflictingPlan = Buffer.from(JSON.stringify({ runId: connection.runId, agentId: "B", items: [], changed: true }));
    expect((await upload(controller.baseUrl, "B", connection.runId, "plan.json", conflictingPlan)).status).toBe(400);
    expect((await upload(controller.baseUrl, "B", connection.runId, "summary.json", Buffer.from("{}"), "0".repeat(64))).status).toBe(400);
    expect((await upload(controller.baseUrl, "B", connection.runId, "other.txt", Buffer.from("x"))).status).toBe(400);

    for (const agentId of ["B", "C"] as const) {
      const response = await fetch(`${controller.baseUrl}/api/agents/${agentId}/complete`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ runId: connection.runId }),
      });
      expect(response.status).toBe(200);
    }
    for (let attempt = 0; attempt < 50 && controller.snapshot().run.phase !== "COMPLETE"; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    expect(controller.snapshot()).toMatchObject({ run: { phase: "COMPLETE" }, report: { conclusion: "PASSED" } });
    expect(verify).toHaveBeenCalledTimes(1);
    expect(writeReport).toHaveBeenCalledTimes(1);
    expect(await fs.readFile(path.join(connection.runRoot, "B", "plan.json"), "utf8")).toBe(JSON.stringify({ runId: connection.runId, agentId: "B", items: [] }));
  });
});
