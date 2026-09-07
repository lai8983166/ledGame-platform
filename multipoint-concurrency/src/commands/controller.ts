import fs from "node:fs/promises";
import path from "node:path";
import readline from "node:readline/promises";
import { failCli, optionalArg, parseArgs } from "../cli.js";
import { readJsonFile, resolveControllerConfig } from "../config.js";
import { createUniqueRunId, isPortAvailable, startCenter } from "../center.js";
import { startControllerServer } from "../controller.js";
import type { TimedProfileName } from "../types.js";

async function chooseProfile(value: string | undefined): Promise<TimedProfileName> {
  if (value) return value as TimedProfileName;
  if (!process.stdin.isTTY) return "quick";
  const terminal = readline.createInterface({ input: process.stdin, output: process.stdout });
  try {
    process.stdout.write("请选择测试档位：\n1. quick（5 分钟）\n2. standard（30 分钟）\n3. soak（2 小时）\n4. overnight（12 小时）\n");
    const answer = (await terminal.question("输入 1-4，直接回车默认 quick：")).trim();
    return ({ "": "quick", "1": "quick", "2": "standard", "3": "soak", "4": "overnight" } as const)[answer as ""]
      ?? (() => { throw new Error("档位选择无效，只能输入 1、2、3 或 4"); })();
  } finally { terminal.close(); }
}

async function fileExists(file: string | undefined): Promise<boolean> {
  return Boolean(file && await fs.stat(file).then(value => value.isFile()).catch(() => false));
}

async function applyInstalledLicense(raw: Record<string, unknown>, cwd: string): Promise<Record<string, unknown>> {
  const configured = raw.activationLicensePath ? path.resolve(cwd, String(raw.activationLicensePath)) : undefined;
  if (await fileExists(configured)) return raw;
  const appData = process.env.APPDATA;
  const installed = appData ? path.join(appData, "LED Game Member Admin", "activation", "license.json") : undefined;
  return await fileExists(installed) ? { ...raw, activationLicensePath: installed } : raw;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const cwd = process.cwd();
  const configPath = path.resolve(optionalArg(args, "config") ?? "config/center.json");
  const profile = await chooseProfile(optionalArg(args, "profile"));
  const raw = await applyInstalledLicense(await readJsonFile<Record<string, unknown>>(configPath), cwd);
  const initial = resolveControllerConfig(raw, profile, cwd);
  const runId = await createUniqueRunId(initial.center.testRoot);
  const config = { ...initial, center: { ...initial.center, runId } };
  if (!await isPortAvailable(config.controlPort)) throw new Error(`控制器端口 ${config.controlPort} 已被占用`);
  const connection = await startCenter(config.center);
  const controller = await startControllerServer(config, connection, {
    onEvent: message => process.stdout.write(`[${new Date().toLocaleTimeString("zh-CN", { hour12: false })}] ${message}\n`),
  });
  process.stdout.write([
    "",
    "A 机并发测试已启动。",
    `运行编号：${connection.runId}`,
    `测试档位：${config.profile}`,
    `B/C 控制器地址：${controller.baseUrl}`,
    `测试业务地址：${connection.platformBaseUrl}`,
    "现在分别在 B、C 机双击 join.cmd；两台均就绪后会自动开始。",
    "完成后将在本窗口显示结论并自动打开中文报告。按 Ctrl+C 只关闭控制器，会员管理端请从窗口正常退出。",
    "",
  ].join("\n"));
  const timer = setInterval(() => {
    const status = controller.snapshot();
    const line = (["B", "C"] as const).map(id => {
      const agent = status.agents[id];
      return agent ? `${id}=${agent.possiblyOffline ? "可能离线" : agent.phase}` : `${id}=未加入`;
    }).join("，");
    process.stdout.write(`当前状态：${status.run.phase}；${line}\n`);
  }, 30_000);
  await new Promise<void>(resolve => {
    const stop = async () => {
      clearInterval(timer);
      await controller.close().catch(() => undefined);
      resolve();
    };
    process.once("SIGINT", () => void stop());
    process.once("SIGTERM", () => void stop());
  });
}

main().catch(failCli);
