import path from "node:path";
import { failCli, optionalArg, parseArgs, requiredArg } from "../cli.js";
import { readJsonFile, resolveAgentConfig } from "../config.js";
import { runAgent } from "../agent.js";
import type { ConnectionInfo } from "../types.js";

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const connectionPath = path.resolve(requiredArg(args, "connection"));
  const connection = await readJsonFile<ConnectionInfo>(connectionPath);
  const overrides = optionalArg(args, "config")
    ? await readJsonFile<Record<string, unknown>>(path.resolve(optionalArg(args, "config")!)) : {};
  const config = resolveAgentConfig(connection, {
    ...overrides,
    agentId: requiredArg(args, "agent-id"),
    profile: optionalArg(args, "profile") ?? overrides.profile ?? "smoke",
    outputRoot: optionalArg(args, "output-root") ?? overrides.outputRoot ?? "runs",
    safetyConfirmation: overrides.safetyConfirmation ?? connection.safetyConfirmation,
  }, process.cwd());
  process.stdout.write(`开始运行：${config.runId} / 节点 ${config.agentId} / ${config.profile}\n`);
  const summary = await runAgent(config);
  process.stdout.write(`计划 ${summary.planned}，完成 ${summary.attempted}，成功 ${summary.succeeded}，失败 ${summary.failed}。\n`);
  if (summary.failed > 0 || summary.incomplete > 0) process.exitCode = 2;
}

main().catch(failCli);
