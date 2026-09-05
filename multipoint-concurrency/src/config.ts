import fs from "node:fs/promises";
import path from "node:path";
import net from "node:net";
import type { AgentConfig, CenterConfig, ConnectionInfo, LoadProfile, ProfileName } from "./types.js";
import { SAFETY_CONFIRMATION } from "./types.js";

export const PROFILES: Record<ProfileName, LoadProfile> = {
  smoke: {
    registrationWorkers: 1,
    gameWorkers: 1,
    iterationsPerWorker: 2,
    maxDurationSeconds: 120,
    requestTimeoutMs: 5_000,
    durationMinutes: 1440,
  },
  load: {
    registrationWorkers: 3,
    gameWorkers: 6,
    iterationsPerWorker: 10,
    maxDurationSeconds: 900,
    requestTimeoutMs: 5_000,
    durationMinutes: 1440,
  },
};

const RUN_ID_PATTERN = /^[A-Z0-9][A-Z0-9-]{2,39}$/;
const AGENT_ID_PATTERN = /^[A-Z0-9][A-Z0-9-]{0,15}$/;

export async function readJsonFile<T = unknown>(file: string): Promise<T> {
  return JSON.parse(await fs.readFile(file, "utf8")) as T;
}

export function generateRunId(date = new Date()): string {
  const digits = date.toISOString().replace(/[-:TZ.]/g, "").slice(0, 14);
  return `CONC-${digits}`;
}

export function normalizeRunId(value: unknown): string {
  const runId = String(value ?? "").trim().toUpperCase();
  if (!RUN_ID_PATTERN.test(runId)) throw new Error("runId 必须为 3 至 40 位大写字母、数字或短横线");
  return runId;
}

export function normalizeAgentId(value: unknown): string {
  const agentId = String(value ?? "").trim().toUpperCase();
  if (!AGENT_ID_PATTERN.test(agentId)) throw new Error("agentId 必须为 1 至 16 位大写字母、数字或短横线");
  return agentId;
}

function requireSafety(value: unknown): string {
  if (value !== SAFETY_CONFIRMATION) {
    throw new Error(`安全确认值必须是 ${SAFETY_CONFIRMATION}`);
  }
  return SAFETY_CONFIRMATION;
}

function numberInRange(value: unknown, name: string, min: number, max: number): number {
  const result = Number(value);
  if (!Number.isInteger(result) || result < min || result > max) {
    throw new Error(`${name} 必须是 ${min} 到 ${max} 的整数`);
  }
  return result;
}

export function normalizeBaseUrl(value: unknown, name = "平台地址"): string {
  let url: URL;
  try { url = new URL(String(value ?? "").trim()); }
  catch { throw new Error(`${name}不是有效 URL`); }
  if (url.protocol !== "http:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error(`${name}必须是无路径、账号、查询参数的 http://host:port`);
  }
  if (!url.port) throw new Error(`${name}必须包含端口`);
  return url.toString().replace(/\/$/, "");
}

export function resolveCenterConfig(raw: Record<string, unknown>, cwd = process.cwd()): CenterConfig {
  const testRoot = path.resolve(cwd, String(raw.testRoot ?? "test-results/multipoint-concurrency"));
  const memberAdminExecutable = path.resolve(cwd, String(raw.memberAdminExecutable ?? ""));
  const lanHost = String(raw.lanHost ?? "").trim();
  if (!lanHost || (!net.isIP(lanHost) && !/^[a-z0-9.-]+$/i.test(lanHost))) {
    throw new Error("lanHost 必须是局域网 IP 或主机名");
  }
  return {
    runId: normalizeRunId(raw.runId ?? generateRunId()),
    memberAdminExecutable,
    testRoot,
    lanHost,
    testPort: numberInRange(raw.testPort ?? 18090, "testPort", 1024, 65535),
    normalPlatformUrl: normalizeBaseUrl(raw.normalPlatformUrl ?? "http://127.0.0.1:8090", "正常平台地址"),
    startupTimeoutMs: numberInRange(raw.startupTimeoutMs ?? 60_000, "startupTimeoutMs", 1_000, 300_000),
    safetyConfirmation: requireSafety(raw.safetyConfirmation),
  };
}

export function resolveAgentConfig(
  connection: ConnectionInfo,
  raw: Record<string, unknown>,
  cwd = process.cwd(),
): AgentConfig {
  const profile = String(raw.profile ?? "smoke") as ProfileName;
  if (!(profile in PROFILES)) throw new Error("profile 必须是 smoke 或 load");
  const defaults = PROFILES[profile];
  const config: AgentConfig = {
    runId: normalizeRunId(connection.runId),
    agentId: normalizeAgentId(raw.agentId),
    profile,
    platformBaseUrl: normalizeBaseUrl(connection.platformBaseUrl),
    outputRoot: path.resolve(cwd, String(raw.outputRoot ?? "runs")),
    safetyConfirmation: requireSafety(raw.safetyConfirmation),
    registrationWorkers: numberInRange(raw.registrationWorkers ?? defaults.registrationWorkers, "registrationWorkers", 0, 50),
    gameWorkers: numberInRange(raw.gameWorkers ?? defaults.gameWorkers, "gameWorkers", 0, 50),
    iterationsPerWorker: numberInRange(raw.iterationsPerWorker ?? defaults.iterationsPerWorker, "iterationsPerWorker", 1, 10_000),
    maxDurationSeconds: numberInRange(raw.maxDurationSeconds ?? defaults.maxDurationSeconds, "maxDurationSeconds", 10, 86_400),
    requestTimeoutMs: numberInRange(raw.requestTimeoutMs ?? defaults.requestTimeoutMs, "requestTimeoutMs", 100, 120_000),
    durationMinutes: numberInRange(raw.durationMinutes ?? defaults.durationMinutes, "durationMinutes", 1, 1440),
  };
  if (config.registrationWorkers + config.gameWorkers < 1) throw new Error("至少需要一个注册或游戏工作单元");
  const planned = (config.registrationWorkers + config.gameWorkers) * config.iterationsPerWorker;
  if (planned > 20_000) throw new Error("单个代理计划数不能超过 20000");
  return config;
}
