import path from "node:path";
import fs from "node:fs/promises";
import { buildPlan } from "../plan.js";
import { renderPreview } from "../timed-plan.js";
import { failCli, optionalArg, parseArgs, requiredArg } from "../cli.js";
import { readJsonFile, resolveAgentConfig } from "../config.js";
import { runAgent } from "../agent.js";
import { stepSucceeded } from "../platform-client.js";
import type { ConnectionInfo } from "../types.js";

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const connectionPath = path.resolve(requiredArg(args, "connection"));
  const connection = await readJsonFile<ConnectionInfo>(connectionPath);
  const overrides = optionalArg(args, "config")
    ? await readJsonFile<Record<string, unknown>>(path.resolve(optionalArg(args, "config")!)) : {};
  if (optionalArg(args, "preview-only") === "true") {
    const ids = (optionalArg(args, "agent-ids") ?? "B,C").split(",").map(id => id.trim().toUpperCase());
    if (new Set(ids).size !== ids.length || ids.length !== 2) throw new Error("预览需要两个不同的 agent-ids，例如 B,C");
    const plans = ids.map(agentId => buildPlan(resolveAgentConfig(connection, { ...overrides, agentId,
      profile: optionalArg(args, "profile") ?? overrides.profile ?? "quick", safetyConfirmation: connection.safetyConfirmation })));
    const preview = renderPreview(plans);
    const directory = path.resolve(optionalArg(args, "output-root") ?? "runs", connection.runId, `preview-${plans[0]!.profile}`);
    await fs.mkdir(directory, { recursive: true });
    for (const plan of plans) await fs.writeFile(path.join(directory, `plan-${plan.agentId}.json`), JSON.stringify(plan, null, 2) + "\n", "utf8");
    await fs.writeFile(path.join(directory, "执行预览.md"), preview, "utf8");
    process.stdout.write(preview + `\n预览文件：${directory}\n`);
    return;
  }
  const config = resolveAgentConfig(connection, {
    ...overrides,
    agentId: requiredArg(args, "agent-id"),
    profile: optionalArg(args, "profile") ?? overrides.profile ?? "quick",
    outputRoot: optionalArg(args, "output-root") ?? overrides.outputRoot ?? "runs",
    safetyConfirmation: overrides.safetyConfirmation ?? connection.safetyConfirmation,
  }, process.cwd());
  process.stdout.write(`开始运行：${config.runId} / 节点 ${config.agentId} / ${config.profile}\n`);
  const summary = await runAgent(config, { onProgress: message => process.stdout.write(message + "\n") });
  const queryFailures = summary.queryResults?.filter(step => !stepSucceeded(step)).length ?? 0;
  process.stdout.write(`业务计划 ${summary.planned}，完成 ${summary.attempted}，成功 ${summary.succeeded}，失败 ${summary.failed}；管理查询失败 ${queryFailures}。\n`);
  if (summary.failed > 0 || summary.incomplete > 0 || summary.http5xx > 0 || summary.timeouts > 0 || summary.networkErrors > 0 || queryFailures > 0 || summary.schedule?.executionPassed === false) process.exitCode = 2;
}

main().catch(failCli);
