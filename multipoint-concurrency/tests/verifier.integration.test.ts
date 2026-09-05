import http from "node:http";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { verifyRun } from "../src/verifier.js";
import { FORMAT_VERSION, type AgentSummary, type FlowResult, type PlanFile, type VerifyConfig } from "../src/types.js";

interface FixtureOptions {
  missingMember?: boolean;
  interruptedAgent?: boolean;
  uncertainAgent?: boolean;
  nonOverlapping?: boolean;
  centerLog?: string;
}

const temporaryRoots: string[] = [];
const servers: http.Server[] = [];

afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))));
  await Promise.all(temporaryRoots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true })));
});

async function writeJson(file: string, value: unknown) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function createFixture(options: FixtureOptions = {}): Promise<VerifyConfig> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-verify-integration-"));
  temporaryRoots.push(root);
  const members = new Map<string, { id: number; phone: string; name: string }>();
  const wristbands = new Map<string, { uid: string; status: string; durationMinutes: number; memberId: number }>();

  const server = http.createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    response.setHeader("content-type", "application/json");
    if (url.pathname === "/api/members") {
      const member = members.get(url.searchParams.get("phone") ?? "");
      response.end(JSON.stringify(options.missingMember ? [] : member ? [member] : []));
      return;
    }
    if (url.pathname.startsWith("/api/wristbands/")) {
      const wristband = wristbands.get(decodeURIComponent(url.pathname.slice("/api/wristbands/".length)));
      response.statusCode = wristband ? 200 : 404;
      response.end(JSON.stringify(wristband ?? { code: "NOT_FOUND" }));
      return;
    }
    response.statusCode = 404;
    response.end(JSON.stringify({ code: "NOT_FOUND" }));
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("无法取得测试服务器端口");
  const platformBaseUrl = `http://127.0.0.1:${address.port}`;
  const runId = "VERIFY-INTEGRATION";
  const baseStart = Date.parse("2026-09-05T00:00:00.000Z");

  for (const [index, agentId] of ["B", "C"].entries()) {
    const phone = `90000000000000${index + 1}`;
    const uid = `8000000000000000000${index + 1}`;
    const memberName = `并发测试-${agentId}`;
    const operationId = `op-${agentId}`;
    const start = new Date(baseStart + (options.nonOverlapping ? index * 120_000 : index * 1_000)).toISOString();
    const end = new Date(Date.parse(start) + 60_000).toISOString();
    const plan: PlanFile = {
      formatVersion: FORMAT_VERSION,
      runId,
      agentId,
      profile: "smoke",
      platformBaseUrl,
      generatedAt: start,
      items: [{ operationId, flowType: "registration", worker: 1, iteration: 1, phone, uid, memberName, durationMinutes: 30 }],
    };
    const interrupted = options.interruptedAgent && agentId === "B";
    const uncertain = options.uncertainAgent && agentId === "B";
    if (!interrupted) {
      members.set(phone, { id: index + 1, phone, name: memberName });
      wristbands.set(uid, { uid, status: "READY", durationMinutes: 30, memberId: index + 1 });
    }
    const results: FlowResult[] = interrupted ? [] : [{
      formatVersion: FORMAT_VERSION,
      operationId,
      flowType: "registration",
      startedAt: start,
      endedAt: end,
      success: !uncertain,
      steps: uncertain ? [{ name: "bind", method: "POST", path: "/api/wristbands/bind", kind: "network", startedAt: start, endedAt: end, durationMs: 25, error: "socket closed" }] : [],
      ...(uncertain ? { error: "socket closed" } : {}),
    }];
    const summary: AgentSummary = {
      formatVersion: FORMAT_VERSION,
      runId,
      agentId,
      profile: "smoke",
      platformBaseUrl,
      startedAt: start,
      endedAt: end,
      planned: 1,
      attempted: interrupted ? 0 : 1,
      succeeded: interrupted || uncertain ? 0 : 1,
      failed: uncertain ? 1 : 0,
      incomplete: interrupted ? 1 : 0,
      http5xx: 0,
      timeouts: 0,
      networkErrors: uncertain ? 1 : 0,
      durationSamplesMs: uncertain ? [25] : interrupted ? [] : [10],
    };
    const directory = path.join(root, `agent-${agentId}`);
    await writeJson(path.join(directory, "plan.json"), plan);
    await fs.writeFile(path.join(directory, "results.jsonl"), results.map((result) => JSON.stringify(result)).join("\n") + (results.length ? "\n" : ""), "utf8");
    await writeJson(path.join(directory, "summary.json"), summary);
  }

  const centerLogPath = path.join(root, "center.log");
  await fs.writeFile(centerLogPath, options.centerLog ?? "center ready\n", "utf8");
  const connectionFile = path.join(root, "connection.json");
  await writeJson(connectionFile, { formatVersion: FORMAT_VERSION, runId, platformBaseUrl, centerLogPath, runRoot: root });
  return {
    connectionFile,
    agentDirectories: [path.join(root, "agent-B"), path.join(root, "agent-C")],
    outputDirectory: path.join(root, "report"),
    requestTimeoutMs: 1_000,
    performanceWarningP95Ms: 5_000,
  };
}

describe("verifyRun controlled failure integration", () => {
  it("passes a complete two-agent reconciliation", async () => {
    const report = await verifyRun(await createFixture());
    expect(report.conclusion).toBe("PASSED");
    expect(report.dataIntegrityPassed).toBe(true);
    expect(report.differences).toEqual([]);
  });

  it("reports persisted data loss after a successful response", async () => {
    const report = await verifyRun(await createFixture({ missingMember: true }));
    expect(report.conclusion).toBe("FAILED");
    expect(report.dataIntegrityPassed).toBe(false);
    expect(report.differences.map((difference) => difference.code)).toContain("MEMBER_COUNT_MISMATCH");
  });

  it("separates an interrupted plan from database loss", async () => {
    const report = await verifyRun(await createFixture({ interruptedAgent: true }));
    expect(report.conclusion).toBe("FAILED");
    expect(report.dataIntegrityPassed).toBe(true);
    expect(report.differences.map((difference) => difference.code)).toContain("PLAN_INCOMPLETE");
  });

  it("recognizes a lost response whose final state was committed", async () => {
    const report = await verifyRun(await createFixture({ uncertainAgent: true }));
    expect(report.conclusion).toBe("FAILED");
    expect(report.dataIntegrityPassed).toBe(true);
    expect(report.counts.uncertainButCommitted).toBe(1);
  });

  it("fails on database lock evidence without relabeling it as a field mismatch", async () => {
    const report = await verifyRun(await createFixture({ centerLog: "SQLITE_BUSY: database is locked\n" }));
    expect(report.conclusion).toBe("FAILED");
    expect(report.dataIntegrityPassed).toBe(true);
    expect(report.differences.map((difference) => difference.code)).toContain("CENTER_LOG_ERROR");
  });

  it("marks non-overlapping agent runs invalid", async () => {
    const report = await verifyRun(await createFixture({ nonOverlapping: true }));
    expect(report.conclusion).toBe("INVALID");
    expect(report.differences.map((difference) => difference.code)).toContain("NO_RUNTIME_OVERLAP");
  });
});
