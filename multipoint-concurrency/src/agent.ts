import fs from "node:fs/promises";
import path from "node:path";
import { buildPlan } from "./plan.js";
import { PlatformClient, stepSucceeded } from "./platform-client.js";
import { FORMAT_VERSION, type AgentConfig, type AgentSummary, type FlowResult, type PlanFile, type PlanItem, type StepResult } from "./types.js";

export interface AgentRunPaths {
  directory: string;
  plan: string;
  results: string;
  summary: string;
}

export function agentRunPaths(config: AgentConfig): AgentRunPaths {
  const directory = path.join(config.outputRoot, config.runId, config.agentId);
  return {
    directory,
    plan: path.join(directory, "plan.json"),
    results: path.join(directory, "results.jsonl"),
    summary: path.join(directory, "summary.json"),
  };
}

class ResultWriter {
  private tail = Promise.resolve();
  constructor(private readonly file: string) {}
  append(result: FlowResult): Promise<void> {
    this.tail = this.tail.then(() => fs.appendFile(this.file, `${JSON.stringify(result)}\n`, "utf8"));
    return this.tail;
  }
  done(): Promise<void> { return this.tail; }
}

function firstFailure(steps: StepResult[]): StepResult | undefined {
  return steps.find((step) => !stepSucceeded(step));
}

export interface StepClient {
  step(name: string, method: string, path: string, body?: unknown): Promise<StepResult>;
}

async function memberSetup(item: PlanItem, client: StepClient, steps: StepResult[]): Promise<number | null> {
  const charge = await client.step("charge", "POST", "/api/wristbands/charge", {
    uid: item.uid, durationMinutes: item.durationMinutes,
  });
  steps.push(charge);
  if (!stepSucceeded(charge)) return null;

  const lookup = await client.step("memberLookup", "GET", `/api/members?phone=${encodeURIComponent(item.phone)}`);
  steps.push(lookup);
  if (!stepSucceeded(lookup)) return null;
  const existing = Array.isArray(lookup.response) ? lookup.response : [];
  if (existing.length > 0) return null;

  const create = await client.step("memberCreate", "POST", "/api/members", {
    phone: item.phone,
    name: item.memberName,
    avatarId: "default-boy",
    birthday: "2000-01-01",
    gender: "MALE",
    createdBy: "multipoint-concurrency-test",
  });
  steps.push(create);
  if (!stepSucceeded(create) || typeof create.response !== "object" || create.response === null) return null;
  const memberId = Number((create.response as Record<string, unknown>).id);
  if (!Number.isInteger(memberId) || memberId <= 0) return null;

  const bind = await client.step("bind", "POST", "/api/wristbands/bind", { uid: item.uid, memberId });
  steps.push(bind);
  if (!stepSucceeded(bind)) return null;
  return memberId;
}

export async function executeFlow(item: PlanItem, client: StepClient): Promise<FlowResult> {
  const startedAt = new Date().toISOString();
  const steps: StepResult[] = [];
  const memberId = await memberSetup(item, client, steps);
  if (memberId && item.flowType === "registration") {
    steps.push(await client.step("wristbandQuery", "GET", `/api/wristbands/${encodeURIComponent(item.uid)}`));
  }
  if (memberId && item.flowType === "game") {
    const activate = await client.step("activate", "POST", "/api/game-access/activate", { uid: item.uid });
    steps.push(activate);
    if (stepSucceeded(activate)) {
      const start = await client.step("startGame", "POST", "/api/game-plays/start", {
        uid: item.uid,
        deviceId: item.deviceId,
        roomId: item.roomId,
        externalSessionId: item.externalSessionId,
        gameId: "concurrency-test-game",
        gameName: "并发测试游戏",
      });
      steps.push(start);
      const playId = typeof start.response === "object" && start.response !== null
        ? Number((start.response as Record<string, unknown>).id) : NaN;
      if (stepSucceeded(start) && Number.isInteger(playId) && playId > 0) {
        const settle = await client.step("settleGame", "PUT", `/api/game-plays/${playId}/result`, {
          success: true,
          terminationReason: "NATURAL_COMPLETED",
          rawScore: item.rawScore,
          resultPayload: { runId: item.operationId, source: "multipoint-concurrency-test" },
        });
        steps.push(settle);
        if (stepSucceeded(settle)) {
          steps.push(await client.step("playerInfo", "GET", `/api/player-info?phone=${encodeURIComponent(item.phone)}`));
        }
      }
    }
  }
  const failed = firstFailure(steps);
  const expectedLastStep = item.flowType === "registration" ? "wristbandQuery" : "playerInfo";
  const success = !failed && steps.at(-1)?.name === expectedLastStep;
  return {
    formatVersion: FORMAT_VERSION,
    operationId: item.operationId,
    flowType: item.flowType,
    startedAt,
    endedAt: new Date().toISOString(),
    success,
    steps,
    ...(!success ? { error: failed
      ? `${failed.name}: ${failed.kind === "http" ? `HTTP ${failed.status}` : failed.kind}`
      : "流程未完成或响应缺少必要字段" } : {}),
  };
}

function groupWorkers(plan: PlanFile): PlanItem[][] {
  const groups = new Map<string, PlanItem[]>();
  for (const item of plan.items) {
    const key = `${item.flowType}:${item.worker}`;
    groups.set(key, [...(groups.get(key) ?? []), item]);
  }
  return [...groups.values()].map((items) => items.sort((a, b) => a.iteration - b.iteration));
}

export interface RunAgentDependencies {
  client?: StepClient;
  now?: () => Date;
}

export async function runAgent(config: AgentConfig, dependencies: RunAgentDependencies = {}): Promise<AgentSummary> {
  const paths = agentRunPaths(config);
  await fs.mkdir(paths.directory, { recursive: true });
  if (await fs.stat(paths.plan).then(() => true).catch(() => false)) {
    throw new Error(`运行目录已经存在计划，禁止覆盖：${paths.directory}`);
  }
  const now = dependencies.now ?? (() => new Date());
  const plan = buildPlan(config, now());
  await fs.writeFile(paths.plan, `${JSON.stringify(plan, null, 2)}\n`, "utf8");
  await fs.writeFile(paths.results, "", "utf8");
  const writer = new ResultWriter(paths.results);
  const client = dependencies.client ?? new PlatformClient(config.platformBaseUrl, config.requestTimeoutMs);
  const startedAt = now();
  const deadline = startedAt.getTime() + config.maxDurationSeconds * 1000;
  const results: FlowResult[] = [];

  await Promise.all(groupWorkers(plan).map(async (items) => {
    for (const item of items) {
      if (Date.now() >= deadline) break;
      const result = await executeFlow(item, client);
      results.push(result);
      await writer.append(result);
    }
  }));
  await writer.done();
  const endedAt = now();
  const steps = results.flatMap((result) => result.steps);
  const summary: AgentSummary = {
    formatVersion: FORMAT_VERSION,
    runId: config.runId,
    agentId: config.agentId,
    profile: config.profile,
    platformBaseUrl: config.platformBaseUrl,
    startedAt: startedAt.toISOString(),
    endedAt: endedAt.toISOString(),
    planned: plan.items.length,
    attempted: results.length,
    succeeded: results.filter((result) => result.success).length,
    failed: results.filter((result) => !result.success).length,
    incomplete: plan.items.length - results.length,
    http5xx: steps.filter((step) => step.kind === "http" && Number(step.status) >= 500).length,
    timeouts: steps.filter((step) => step.kind === "timeout").length,
    networkErrors: steps.filter((step) => step.kind === "network").length,
    durationSamplesMs: steps.map((step) => step.durationMs),
  };
  await fs.writeFile(paths.summary, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  return summary;
}

export async function readResults(file: string): Promise<FlowResult[]> {
  const content = await fs.readFile(file, "utf8");
  return content.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line) as FlowResult);
}
