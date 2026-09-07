import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import http from "node:http";
import path from "node:path";
import { agentRunPaths, runAgent } from "./agent.js";
import { normalizeBaseUrl, normalizeRunId, normalizeTimedProfile, resolveAgentConfig } from "./config.js";
import { FORMAT_VERSION, SAFETY_CONFIRMATION, type AgentSummary, type ConnectionInfo, type ControllerRunInfo, type RemoteAgentConfig, type RemoteAgentPhase } from "./types.js";

export interface RemoteAgentDependencies {
  fetchImpl?: typeof fetch;
  sleep?: (milliseconds: number) => Promise<void>;
  now?: () => Date;
  onMessage?: (message: string) => void;
  run?: typeof runAgent;
}

const DEFAULT_SLEEP = (milliseconds: number) => new Promise<void>(resolve => setTimeout(resolve, milliseconds));

class ControllerUnavailableError extends Error {}

function validateRunInfo(value: unknown): ControllerRunInfo {
  if (!value || typeof value !== "object") throw new Error("A 机返回的当前批次不是有效对象");
  const raw = value as Record<string, unknown>;
  const phase = String(raw.phase ?? "");
  if (!new Set(["WAITING", "RUNNING", "VERIFYING", "COMPLETE", "FAILED"]).has(phase)) throw new Error("A 机返回的批次阶段无效");
  const startedAt = String(raw.startedAt ?? "");
  if (!Number.isFinite(Date.parse(startedAt))) throw new Error("A 机返回的批次开始时间无效");
  const startAt = raw.startAt === undefined ? undefined : String(raw.startAt);
  if (startAt !== undefined && !Number.isFinite(Date.parse(startAt))) throw new Error("A 机返回的共同开始时间无效");
  return {
    formatVersion: Number(raw.formatVersion),
    runId: normalizeRunId(raw.runId),
    profile: normalizeTimedProfile(raw.profile),
    platformBaseUrl: normalizeBaseUrl(raw.platformBaseUrl),
    phase: phase as ControllerRunInfo["phase"],
    startedAt,
    ...(startAt ? { startAt } : {}),
  };
}

async function requestJson(
  url: string,
  options: RequestInit,
  fetchImpl: typeof fetch,
): Promise<Record<string, unknown>> {
  let response: Response;
  try { response = await fetchImpl(url, { ...options, signal: AbortSignal.timeout(5_000) }); }
  catch (error) { throw new ControllerUnavailableError(error instanceof Error ? error.message : String(error)); }
  const body = await response.json().catch(() => ({})) as Record<string, unknown>;
  if (!response.ok) {
    const error = new Error(`${response.status}: ${String(body.error ?? "A 机控制器请求失败")}`);
    if (response.status >= 500) throw new ControllerUnavailableError(error.message);
    throw error;
  }
  return body;
}

export async function fetchCurrentRun(controllerUrl: string, fetchImpl: typeof fetch = fetch): Promise<ControllerRunInfo> {
  const run = validateRunInfo(await requestJson(`${controllerUrl}/api/run`, {}, fetchImpl));
  if (run.formatVersion !== FORMAT_VERSION) throw new Error(`控制协议版本不兼容：${run.formatVersion}`);
  return run;
}

export async function waitForCurrentRun(config: RemoteAgentConfig, dependencies: RemoteAgentDependencies = {}): Promise<ControllerRunInfo> {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const sleep = dependencies.sleep ?? DEFAULT_SLEEP;
  const now = dependencies.now ?? (() => new Date());
  const deadline = now().getTime() + config.waitTimeoutMs;
  let lastMessage = "";
  while (now().getTime() < deadline) {
    try { return await fetchCurrentRun(config.controllerUrl, fetchImpl); }
    catch (error) {
      if (!(error instanceof ControllerUnavailableError)) throw error;
      const message = error instanceof Error ? error.message : String(error);
      if (message !== lastMessage) dependencies.onMessage?.(`等待 A 机控制器：${message}。请检查 A 机程序、网络或防火墙。`);
      lastMessage = message;
      await sleep(1_000);
    }
  }
  throw new Error(`等待 A 机控制器超时（${config.waitTimeoutMs}ms），未发送任何业务写请求`);
}

export async function postRemoteStatus(
  config: RemoteAgentConfig,
  runId: string,
  phase: RemoteAgentPhase,
  message: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  await requestJson(`${config.controllerUrl}/api/agents/${config.agentId}/status`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ runId, phase, message: message.slice(0, 2000) }),
  }, fetchImpl);
}

export async function waitForSharedStart(
  config: RemoteAgentConfig,
  initialRun: ControllerRunInfo,
  dependencies: RemoteAgentDependencies = {},
): Promise<ControllerRunInfo> {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const sleep = dependencies.sleep ?? DEFAULT_SLEEP;
  const now = dependencies.now ?? (() => new Date());
  const deadline = now().getTime() + config.waitTimeoutMs;
  dependencies.onMessage?.(`已加入 ${initialRun.runId}，等待 B/C 均就绪。`);
  let lastHeartbeat = 0;
  while (now().getTime() < deadline) {
    if (now().getTime() - lastHeartbeat >= 10_000 || lastHeartbeat === 0) {
      try {
        await postRemoteStatus(config, initialRun.runId, "READY", "已取得当前批次，等待另一台压力机", fetchImpl);
        lastHeartbeat = now().getTime();
      } catch (error) {
        if (!(error instanceof ControllerUnavailableError)) throw error;
        dependencies.onMessage?.(`等待 A 机恢复连接：${error instanceof Error ? error.message : String(error)}。请检查网络或防火墙。`);
        await sleep(1_000);
        continue;
      }
    }
    let run: ControllerRunInfo;
    try { run = await fetchCurrentRun(config.controllerUrl, fetchImpl); }
    catch (error) {
      if (!(error instanceof ControllerUnavailableError)) throw error;
      dependencies.onMessage?.(`等待 A 机恢复连接：${error instanceof Error ? error.message : String(error)}。请检查网络或防火墙。`);
      await sleep(1_000);
      continue;
    }
    if (run.runId !== initialRun.runId) throw new Error("A 机当前批次已变化，拒绝混入其他 runId");
    if (run.phase === "FAILED" || run.phase === "COMPLETE" || run.phase === "VERIFYING") throw new Error(`当前批次已处于 ${run.phase}，不能开始新负载`);
    if (run.startAt) {
      const waitMs = Date.parse(run.startAt) - now().getTime();
      if (waitMs > 0) await sleep(waitMs);
      return run;
    }
    await sleep(1_000);
  }
  throw new Error("等待另一台压力机就绪超时，未发送任何业务写请求");
}

async function sha256File(file: string): Promise<string> {
  const hash = createHash("sha256");
  await new Promise<void>((resolve, reject) => {
    const stream = createReadStream(file);
    stream.on("data", chunk => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", resolve);
  });
  return hash.digest("hex");
}

async function uploadOnce(controllerUrl: string, runId: string, agentId: string, name: string, file: string): Promise<void> {
  const stat = await fs.stat(file);
  const hash = await sha256File(file);
  const url = new URL(`${controllerUrl}/api/agents/${agentId}/artifacts/${encodeURIComponent(name)}`);
  await new Promise<void>((resolve, reject) => {
    const request = http.request(url, {
      method: "PUT",
      headers: { "x-run-id": runId, "x-content-sha256": hash, "content-length": stat.size },
    }, response => {
      const chunks: Buffer[] = [];
      response.on("data", chunk => chunks.push(Buffer.from(chunk)));
      response.on("end", () => {
        if (response.statusCode && response.statusCode >= 200 && response.statusCode < 300) resolve();
        else {
          let detail = "上传失败";
          try { detail = String((JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>).error ?? detail); } catch { /* keep fallback */ }
          reject(new Error(`${response.statusCode ?? 0}: ${detail}`));
        }
      });
    });
    request.setTimeout(30_000, () => request.destroy(new Error("上传超时")));
    request.on("error", reject);
    createReadStream(file).on("error", reject).pipe(request);
  });
}

export async function uploadEvidence(
  config: RemoteAgentConfig,
  runId: string,
  directory: string,
  dependencies: RemoteAgentDependencies = {},
): Promise<void> {
  const sleep = dependencies.sleep ?? DEFAULT_SLEEP;
  for (const name of ["plan.json", "results.jsonl", "summary.json"]) {
    const file = path.join(directory, name);
    let lastError: unknown;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        await uploadOnce(config.controllerUrl, runId, config.agentId, name, file);
        dependencies.onMessage?.(`已回传 ${name}`);
        lastError = undefined;
        break;
      } catch (error) {
        lastError = error;
        if (attempt < 3) await sleep(attempt * 1_000);
      }
    }
    if (lastError) throw new Error(`${name} 回传失败，本机证据仍在 ${directory}：${lastError instanceof Error ? lastError.message : String(lastError)}`);
  }
}

export async function runRemoteAgent(config: RemoteAgentConfig, dependencies: RemoteAgentDependencies = {}): Promise<AgentSummary> {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  let run: ControllerRunInfo | undefined;
  try {
    run = await waitForCurrentRun(config, dependencies);
    run = await waitForSharedStart(config, run, dependencies);
    const connection: ConnectionInfo = {
      formatVersion: FORMAT_VERSION,
      runId: run.runId,
      platformBaseUrl: run.platformBaseUrl,
      testPort: Number(new URL(run.platformBaseUrl).port),
      centerLogPath: "",
      runRoot: "",
      generatedAt: run.startedAt,
      safetyConfirmation: SAFETY_CONFIRMATION,
    };
    const agentConfig = resolveAgentConfig(connection, {
      agentId: config.agentId,
      profile: run.profile,
      outputRoot: config.outputRoot,
      safetyConfirmation: SAFETY_CONFIRMATION,
    });
    await postRemoteStatus(config, run.runId, "RUNNING", "业务负载已开始", fetchImpl);
    const summary = await (dependencies.run ?? runAgent)(agentConfig, {
      onProgress: message => {
        dependencies.onMessage?.(message);
        void postRemoteStatus(config, run!.runId, "RUNNING", message, fetchImpl).catch(() => undefined);
      },
    });
    const directory = agentRunPaths(agentConfig).directory;
    await postRemoteStatus(config, run.runId, "UPLOADING", "业务负载已结束，正在回传验收证据", fetchImpl).catch(() => undefined);
    await uploadEvidence(config, run.runId, directory, dependencies);
    await requestJson(`${config.controllerUrl}/api/agents/${config.agentId}/complete`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ runId: run.runId }),
    }, fetchImpl);
    dependencies.onMessage?.(`本机执行完成，证据保留在：${directory}`);
    return summary;
  } catch (error) {
    if (run) await postRemoteStatus(config, run.runId, "FAILED", error instanceof Error ? error.message : String(error), fetchImpl).catch(() => undefined);
    throw error;
  }
}
