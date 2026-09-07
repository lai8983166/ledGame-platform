import fs from "node:fs/promises";
import path from "node:path";
import { readJsonFile, resolveRemoteAgentConfig } from "./config.js";
import type { RemoteAgentConfig } from "./types.js";

export async function readRemoteAgentLocalConfig(file: string, cwd = process.cwd()): Promise<RemoteAgentConfig> {
  return resolveRemoteAgentConfig(await readJsonFile<Record<string, unknown>>(file), cwd);
}

export async function writeRemoteAgentLocalConfig(
  file: string,
  value: Record<string, unknown>,
  cwd = process.cwd(),
): Promise<RemoteAgentConfig> {
  const config = resolveRemoteAgentConfig(value, cwd);
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify({
    controllerUrl: config.controllerUrl,
    agentId: config.agentId,
    outputRoot: value.outputRoot ?? "runs",
    waitTimeoutMs: config.waitTimeoutMs,
  }, null, 2)}\n`, "utf8");
  return config;
}
