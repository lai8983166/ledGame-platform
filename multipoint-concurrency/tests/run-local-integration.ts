import fs from "node:fs/promises";
import path from "node:path";
import { runAgent } from "../src/agent.js";
import { verifyRun, writeVerificationReport } from "../src/verifier.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type AgentConfig, type ConnectionInfo } from "../src/types.js";

async function main() {
  const baseUrl = process.argv[2] ?? "http://127.0.0.1:18123";
  const root = path.resolve(process.argv[3] ?? "test-results/multipoint-concurrency-integration/run");
  const runId = "CONC-LOCAL-INTEGRATION";
  const common = { runId, profile: "smoke" as const, platformBaseUrl: baseUrl, outputRoot: root, safetyConfirmation: SAFETY_CONFIRMATION, registrationWorkers: 2, gameWorkers: 2, iterationsPerWorker: 2, maxDurationSeconds: 120, requestTimeoutMs: 5000, durationMinutes: 30 };
  const configs: AgentConfig[] = [{ ...common, agentId: "B" }, { ...common, agentId: "C" }];
  const connection: ConnectionInfo = { formatVersion: FORMAT_VERSION, runId, platformBaseUrl: baseUrl, testPort: Number(new URL(baseUrl).port), centerLogPath: path.join(root, runId, "center.log"), runRoot: path.join(root, runId), generatedAt: new Date().toISOString(), safetyConfirmation: SAFETY_CONFIRMATION };
  await fs.mkdir(connection.runRoot, { recursive: true });
  await fs.writeFile(connection.centerLogPath, "local integration center started\n", "utf8");
  const connectionFile = path.join(connection.runRoot, "connection.json");
  await fs.writeFile(connectionFile, `${JSON.stringify(connection, null, 2)}\n`, "utf8");
  const summaries = await Promise.all(configs.map((config) => runAgent(config)));
  const outputDirectory = path.join(connection.runRoot, "report");
  const report = await verifyRun({ connectionFile, agentDirectories: configs.map((config) => path.join(root, runId, config.agentId)), outputDirectory, requestTimeoutMs: 5000, performanceWarningP95Ms: 2000 });
  await writeVerificationReport(report, outputDirectory);
  if (report.conclusion !== "PASSED") throw new Error(`核账失败（代理摘要：${JSON.stringify(summaries)}；差异：${JSON.stringify(report.differences)}）`);
  process.stdout.write(`本机双代理端到端 smoke 通过：${outputDirectory}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack : String(error)}\n`);
  process.exit(1);
});
