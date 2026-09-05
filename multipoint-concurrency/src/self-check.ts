import fs from "node:fs/promises";
import path from "node:path";
import { resolveAgentConfig, resolveCenterConfig } from "./config.js";
import { buildPlan } from "./plan.js";
import { FORMAT_VERSION, type ConnectionInfo } from "./types.js";

async function main() {
  const root = path.resolve(process.argv[2] ?? ".");
  const centerRaw = JSON.parse(await fs.readFile(path.join(root, "config", "center.json"), "utf8"));
  const center = resolveCenterConfig(centerRaw, root);
  const connection: ConnectionInfo = { formatVersion: FORMAT_VERSION, runId: center.runId, platformBaseUrl: `http://${center.lanHost}:${center.testPort}`, testPort: center.testPort, centerLogPath: path.join(center.testRoot, center.runId, "member-admin", "logs", "server.log"), runRoot: path.join(center.testRoot, center.runId), generatedAt: new Date(0).toISOString(), safetyConfirmation: center.safetyConfirmation };
  const all = new Set<string>();
  for (const agentId of ["B", "C"]) {
    const raw = JSON.parse(await fs.readFile(path.join(root, "config", "agent-smoke.json"), "utf8"));
    const plan = buildPlan(resolveAgentConfig(connection, { ...raw, agentId, outputRoot: path.join(root, ".self-check") }, root), new Date(0));
    for (const item of plan.items) {
      for (const identity of [item.operationId, item.phone, item.uid]) {
        if (all.has(identity)) throw new Error(`离线计划身份碰撞：${identity}`);
        all.add(identity);
      }
    }
  }
  process.stdout.write("便携发布物自检通过：配置有效，B/C 离线计划无身份碰撞。\n");
}

main().catch((error) => {
  process.stderr.write(`自检失败：${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
