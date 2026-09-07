import fs from "node:fs/promises";
import path from "node:path";
import readline from "node:readline/promises";
import { failCli, optionalArg, parseArgs } from "../cli.js";
import { resolveRemoteAgentConfig } from "../config.js";
import { runRemoteAgent } from "../remote-agent.js";
import { readRemoteAgentLocalConfig, writeRemoteAgentLocalConfig } from "../agent-local-config.js";

async function exists(file: string): Promise<boolean> {
  return await fs.stat(file).then(value => value.isFile()).catch(() => false);
}

async function promptConfig(): Promise<Record<string, unknown>> {
  if (!process.stdin.isTTY) throw new Error("尚未配置本机，请先在终端运行 join.cmd --controller-url http://A机IP:18091 --agent-id B --save true（C 机使用 C）");
  const terminal = readline.createInterface({ input: process.stdin, output: process.stdout });
  try {
    const address = (await terminal.question("请输入 A 机控制器地址（例如 192.168.50.10 或 http://192.168.50.10:18091）：")).trim();
    const agentId = (await terminal.question("请输入本机身份 B 或 C：")).trim();
    const controllerUrl = /^https?:\/\//i.test(address) ? address : `http://${address}:18091`;
    return { controllerUrl, agentId, outputRoot: "runs", waitTimeoutMs: 300_000 };
  } finally { terminal.close(); }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const configPath = path.resolve(optionalArg(args, "config") ?? "config/agent-local.json");
  const cliUrl = optionalArg(args, "controller-url");
  const cliAgentId = optionalArg(args, "agent-id");
  let raw: Record<string, unknown>;
  let shouldSave = optionalArg(args, "save") === "true";
  if (cliUrl || cliAgentId) {
    if (!cliUrl || !cliAgentId) throw new Error("首次配置必须同时提供 --controller-url 和 --agent-id");
    raw = { controllerUrl: cliUrl, agentId: cliAgentId, outputRoot: "runs", waitTimeoutMs: 300_000 };
  } else if (await exists(configPath)) {
    const saved = await readRemoteAgentLocalConfig(configPath, process.cwd());
    raw = { ...saved };
  }
  else {
    raw = await promptConfig();
    shouldSave = true;
  }
  let config = resolveRemoteAgentConfig(raw, process.cwd());
  if (shouldSave) {
    config = await writeRemoteAgentLocalConfig(configPath, raw, process.cwd());
    process.stdout.write(`本机配置已保存：${configPath}\n`);
  }
  process.stdout.write(`连接 A 机：${config.controllerUrl}；本机身份：${config.agentId}\n`);
  const summary = await runRemoteAgent(config, { onMessage: message => process.stdout.write(`${message}\n`) });
  process.stdout.write(`完成：计划 ${summary.planned}，尝试 ${summary.attempted}，成功 ${summary.succeeded}，失败 ${summary.failed}，未完成 ${summary.incomplete}。\n`);
  if (summary.failed || summary.incomplete || summary.http5xx || summary.timeouts || summary.networkErrors || summary.schedule?.executionPassed === false) process.exitCode = 2;
}

main().catch(failCli);
