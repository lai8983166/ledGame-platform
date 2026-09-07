import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import http, { type IncomingMessage, type ServerResponse } from "node:http";
import path from "node:path";
import { spawn } from "node:child_process";
import type { AddressInfo } from "node:net";
import { FORMAT_VERSION, type ConnectionInfo, type ControllerConfig, type ControllerRunInfo, type RemoteAgentId, type RemoteAgentPhase, type RemoteAgentStatus, type VerificationReport } from "./types.js";
import { normalizeRemoteAgentId, normalizeRunId } from "./config.js";
import { verifyRun, writeVerificationReport } from "./verifier.js";

const ARTIFACT_NAMES = ["plan.json", "results.jsonl", "summary.json"] as const;
type ArtifactName = typeof ARTIFACT_NAMES[number];
const STATUS_PHASES = new Set<RemoteAgentPhase>(["READY", "RUNNING", "UPLOADING", "COMPLETE", "FAILED"]);
const STATUS_BODY_LIMIT = 64 * 1024;

interface StoredAgentStatus extends Omit<RemoteAgentStatus, "possiblyOffline"> {
  lastSeenMs: number;
}

export interface ControllerSnapshot {
  run: ControllerRunInfo;
  agents: Partial<Record<RemoteAgentId, RemoteAgentStatus>>;
  report?: { conclusion: string; markdown: string; json: string };
  error?: string;
}

export interface ControllerDependencies {
  now?: () => Date;
  verify?: (connection: ConnectionInfo, agentDirectories: string[], outputDirectory: string) => Promise<VerificationReport>;
  writeReport?: typeof writeVerificationReport;
  openReport?: (file: string) => void;
  onEvent?: (message: string) => void;
}

export interface RunningController {
  baseUrl: string;
  snapshot(): ControllerSnapshot;
  close(): Promise<void>;
}

function sendJson(response: ServerResponse, status: number, value: unknown): void {
  const body = `${JSON.stringify(value)}\n`;
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(body) });
  response.end(body);
}

async function readJsonBody(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunkValue of request) {
    const chunk = Buffer.isBuffer(chunkValue) ? chunkValue : Buffer.from(chunkValue);
    total += chunk.length;
    if (total > STATUS_BODY_LIMIT) throw new Error("状态请求体过大");
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>; }
  catch { throw new Error("请求体不是有效 JSON"); }
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

async function validateArtifact(file: string, name: ArtifactName, runId: string, agentId: RemoteAgentId): Promise<void> {
  if (name === "results.jsonl") return;
  let value: Record<string, unknown>;
  try { value = JSON.parse(await fs.readFile(file, "utf8")) as Record<string, unknown>; }
  catch { throw new Error(`${name} 不是有效 JSON`); }
  if (value.runId !== runId || value.agentId !== agentId) throw new Error(`${name} 的 runId 或 agentId 与当前上传身份不一致`);
}

function defaultOpenReport(file: string): void {
  if (process.platform !== "win32") return;
  const child = spawn("cmd.exe", ["/c", "start", "", file], { detached: true, stdio: "ignore", windowsHide: true });
  child.unref();
}

export async function startControllerServer(
  config: ControllerConfig,
  connection: ConnectionInfo,
  dependencies: ControllerDependencies = {},
  host = "0.0.0.0",
): Promise<RunningController> {
  const now = dependencies.now ?? (() => new Date());
  const emit = dependencies.onEvent ?? (() => undefined);
  const run: ControllerRunInfo = {
    formatVersion: FORMAT_VERSION,
    runId: normalizeRunId(connection.runId),
    profile: config.profile,
    platformBaseUrl: connection.platformBaseUrl,
    phase: "WAITING",
    startedAt: now().toISOString(),
  };
  const statuses: Partial<Record<RemoteAgentId, StoredAgentStatus>> = {};
  const reportDirectory = path.join(connection.runRoot, "report");
  let report: ControllerSnapshot["report"];
  let controllerError: string | undefined;
  let verificationStarted = false;

  function publicStatus(status: StoredAgentStatus): RemoteAgentStatus {
    return {
      runId: status.runId,
      agentId: status.agentId,
      phase: status.phase,
      message: status.message,
      lastSeenAt: status.lastSeenAt,
      uploadedArtifacts: [...status.uploadedArtifacts],
      possiblyOffline: now().getTime() - status.lastSeenMs > config.offlineAfterMs && status.phase !== "COMPLETE" && status.phase !== "FAILED",
    };
  }

  function snapshot(): ControllerSnapshot {
    const agents: ControllerSnapshot["agents"] = {};
    for (const id of ["B", "C"] as const) if (statuses[id]) agents[id] = publicStatus(statuses[id]!);
    return { run: { ...run }, agents, ...(report ? { report: { ...report } } : {}), ...(controllerError ? { error: controllerError } : {}) };
  }

  function updateStatus(agentId: RemoteAgentId, phase: RemoteAgentPhase, message: string): StoredAgentStatus {
    const date = now();
    const prior = statuses[agentId];
    const status: StoredAgentStatus = {
      runId: run.runId,
      agentId,
      phase,
      message: message.slice(0, 2000),
      lastSeenAt: date.toISOString(),
      lastSeenMs: date.getTime(),
      uploadedArtifacts: prior?.uploadedArtifacts ?? [],
    };
    statuses[agentId] = status;
    if (prior?.phase !== status.phase || prior.message !== status.message) emit(`${agentId}：${phase} - ${status.message}`);
    if (!run.startAt && statuses.B?.phase === "READY" && statuses.C?.phase === "READY") {
      run.startAt = new Date(date.getTime() + config.startDelayMs).toISOString();
      run.phase = "RUNNING";
      emit(`B/C 已就绪，将于 ${run.startAt} 同时开始。`);
    }
    return status;
  }

  async function maybeVerify(): Promise<void> {
    if (verificationStarted || statuses.B?.phase !== "COMPLETE" || statuses.C?.phase !== "COMPLETE") return;
    if (!ARTIFACT_NAMES.every(name => statuses.B!.uploadedArtifacts.includes(name) && statuses.C!.uploadedArtifacts.includes(name))) return;
    verificationStarted = true;
    run.phase = "VERIFYING";
    emit("B/C 证据已收齐，开始自动核账。");
    const agentDirectories = (["B", "C"] as const).map(id => path.join(connection.runRoot, id));
    try {
      const verify = dependencies.verify ?? ((currentConnection, directories, outputDirectory) => verifyRun({
        connectionFile: path.join(currentConnection.runRoot, "connection.json"),
        agentDirectories: directories,
        outputDirectory,
        requestTimeoutMs: 5_000,
        performanceWarningP95Ms: 2_000,
      }));
      const result = await verify(connection, agentDirectories, reportDirectory);
      const files = await (dependencies.writeReport ?? writeVerificationReport)(result, reportDirectory);
      report = { conclusion: result.conclusion, markdown: files.markdown, json: files.json };
      run.phase = "COMPLETE";
      emit(`自动核账完成：${result.conclusion}；中文报告：${files.markdown}`);
      try { (dependencies.openReport ?? defaultOpenReport)(files.markdown); }
      catch (error) { emit(`无法自动打开报告，请手工打开：${files.markdown}（${error instanceof Error ? error.message : String(error)}）`); }
    } catch (error) {
      controllerError = error instanceof Error ? error.message : String(error);
      run.phase = "FAILED";
      emit(`自动核账失败：${controllerError}。本地证据仍保留，可使用 verify.cmd 手工核账。`);
    }
  }

  async function receiveArtifact(request: IncomingMessage, agentId: RemoteAgentId, name: ArtifactName): Promise<{ hash: string; idempotent: boolean }> {
    const declaredRunId = normalizeRunId(request.headers["x-run-id"]);
    if (declaredRunId !== run.runId) throw new Error("上传 runId 与当前批次不一致");
    const declaredHash = String(request.headers["x-content-sha256"] ?? "").toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(declaredHash)) throw new Error("缺少有效的 SHA-256");
    const declaredLength = Number(request.headers["content-length"] ?? NaN);
    if (!Number.isInteger(declaredLength) || declaredLength < 0 || declaredLength > config.maxArtifactBytes) throw new Error("证据文件大小无效或超过上限");
    const directory = path.join(connection.runRoot, agentId);
    await fs.mkdir(directory, { recursive: true });
    const target = path.join(directory, name);
    const temporary = path.join(directory, `.${name}.${randomUUID()}.tmp`);
    const hash = createHash("sha256");
    let received = 0;
    try {
      const handle = await fs.open(temporary, "wx");
      try {
        for await (const chunkValue of request) {
          const chunk = Buffer.isBuffer(chunkValue) ? chunkValue : Buffer.from(chunkValue);
          received += chunk.length;
          if (received > config.maxArtifactBytes || received > declaredLength) throw new Error("实际证据大小超过声明值或上限");
          hash.update(chunk);
          await handle.write(chunk);
        }
      } finally {
        await handle.close();
      }
      if (received !== declaredLength) throw new Error("实际证据大小与声明值不一致");
      const actualHash = hash.digest("hex");
      if (actualHash !== declaredHash) throw new Error("证据 SHA-256 校验失败");
      await validateArtifact(temporary, name, run.runId, agentId);
      const existing = await fs.stat(target).then(() => true).catch(() => false);
      if (existing) {
        if (await sha256File(target) !== actualHash) throw new Error("A 机已存在同名但内容不同的证据，禁止覆盖");
        await fs.rm(temporary, { force: true });
        return { hash: actualHash, idempotent: true };
      }
      try { await fs.link(temporary, target); }
      catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "EEXIST" || await sha256File(target) !== actualHash) throw error;
      }
      await fs.rm(temporary, { force: true });
      const status = statuses[agentId] ?? updateStatus(agentId, "UPLOADING", "正在上传验收证据");
      if (!status.uploadedArtifacts.includes(name)) status.uploadedArtifacts.push(name);
      emit(`${agentId}：已接收 ${name}`);
      return { hash: actualHash, idempotent: false };
    } catch (error) {
      await fs.rm(temporary, { force: true });
      throw error;
    }
  }

  const server = http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://controller.local");
      if (request.method === "GET" && url.pathname === "/health") return sendJson(response, 200, { ok: true, runId: run.runId });
      if (request.method === "GET" && url.pathname === "/api/run") return sendJson(response, 200, run);
      if (request.method === "GET" && url.pathname === "/api/status") return sendJson(response, 200, snapshot());

      const statusMatch = url.pathname.match(/^\/api\/agents\/([^/]+)\/status$/);
      if (request.method === "POST" && statusMatch) {
        const agentId = normalizeRemoteAgentId(decodeURIComponent(statusMatch[1]!));
        const body = await readJsonBody(request);
        if (normalizeRunId(body.runId) !== run.runId) return sendJson(response, 409, { error: "状态 runId 与当前批次不一致" });
        const phase = String(body.phase ?? "") as RemoteAgentPhase;
        if (!STATUS_PHASES.has(phase)) return sendJson(response, 400, { error: "代理阶段无效" });
        const status = updateStatus(agentId, phase, String(body.message ?? ""));
        return sendJson(response, 200, { ok: true, status: publicStatus(status), startAt: run.startAt });
      }

      const artifactMatch = url.pathname.match(/^\/api\/agents\/([^/]+)\/artifacts\/([^/]+)$/);
      if (request.method === "PUT" && artifactMatch) {
        const agentId = normalizeRemoteAgentId(decodeURIComponent(artifactMatch[1]!));
        const name = decodeURIComponent(artifactMatch[2]!) as ArtifactName;
        if (!ARTIFACT_NAMES.includes(name)) return sendJson(response, 400, { error: "证据文件名不在白名单中" });
        const result = await receiveArtifact(request, agentId, name);
        return sendJson(response, 200, { ok: true, ...result });
      }

      const completeMatch = url.pathname.match(/^\/api\/agents\/([^/]+)\/complete$/);
      if (request.method === "POST" && completeMatch) {
        const agentId = normalizeRemoteAgentId(decodeURIComponent(completeMatch[1]!));
        const body = await readJsonBody(request);
        if (normalizeRunId(body.runId) !== run.runId) return sendJson(response, 409, { error: "完成通知 runId 与当前批次不一致" });
        const status = statuses[agentId];
        if (!status || !ARTIFACT_NAMES.every(name => status.uploadedArtifacts.includes(name))) return sendJson(response, 409, { error: "该代理的验收证据尚未收齐" });
        updateStatus(agentId, "COMPLETE", "验收证据已回传");
        void maybeVerify();
        return sendJson(response, 200, { ok: true });
      }
      sendJson(response, 404, { error: "控制器接口不存在" });
    } catch (error) {
      sendJson(response, 400, { error: error instanceof Error ? error.message : String(error) });
    }
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.controlPort, host, () => { server.off("error", reject); resolve(); });
  });
  const address = server.address() as AddressInfo;
  return {
    baseUrl: `http://${host === "0.0.0.0" ? config.center.lanHost : host}:${address.port}`,
    snapshot,
    close: () => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())),
  };
}
