import fs from "node:fs/promises";
import path from "node:path";
import { resolveAgentConfig, resolveCenterConfig, resolveControllerConfig, resolveRemoteAgentConfig } from "./config.js";
import { buildPlan } from "./plan.js";
import { FORMAT_VERSION, type ConnectionInfo } from "./types.js";
import { expectedTotals } from "./timed-plan.js";

async function main() {
  const root = path.resolve(process.argv[2] ?? ".");
  const centerRaw = JSON.parse(await fs.readFile(path.join(root, "config", "center.json"), "utf8"));
  const center = resolveCenterConfig(centerRaw, root);
  const controller = resolveControllerConfig(centerRaw, "quick", root);
  if (controller.controlPort === center.testPort) throw new Error("控制器端口与测试业务端口冲突");
  resolveRemoteAgentConfig(JSON.parse(await fs.readFile(path.join(root, "config", "agent-local.example.json"), "utf8")), root);
  for (const entry of ["start-test.cmd", "join.cmd", "center.cmd", "agent.cmd", "verify.cmd"]) {
    if (!await fs.stat(path.join(root, entry)).then(value => value.isFile()).catch(() => false)) throw new Error(`缺少便携入口：${entry}`);
  }
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
  for (const profile of ["quick", "standard", "soak", "overnight"]) {
    const raw = JSON.parse(await fs.readFile(path.join(root, "config", `agent-${profile}.json`), "utf8"));
    if (raw.profile !== profile) throw new Error(`档位模板不一致：${profile}`);
    const plans = ["B", "C"].map(agentId => {
      const plan = buildPlan(resolveAgentConfig(connection, { ...raw, agentId, safetyConfirmation: connection.safetyConfirmation }), new Date(0));
      if (JSON.stringify(plan.expected) !== JSON.stringify(expectedTotals(plan.items))) throw new Error("预期汇总不一致");
      return plan;
    });
    const phones = new Set(plans[0]!.items.map(i => i.phone));
    if (plans[1]!.items.some(i => phones.has(i.phone))) throw new Error("双节点会员身份碰撞");
  }
  process.stdout.write("便携发布物自检通过：A 机启动、B/C 一键加入、原始兜底命令、四档定时配置和双节点离线计划均有效。\n");
}

main().catch((error) => {
  process.stderr.write(`自检失败：${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
