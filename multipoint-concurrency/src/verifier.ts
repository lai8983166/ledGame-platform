import fs from "node:fs/promises";
import path from "node:path";
import { readResults } from "./agent.js";
import { PlatformClient, stepSucceeded } from "./platform-client.js";
import { FORMAT_VERSION } from "./types.js";
import type { AgentSummary, Difference, FlowResult, PlanFile, PlanItem, VerificationReport, VerifyConfig } from "./types.js";

export interface AgentArtifacts {
  directory: string;
  plan: PlanFile;
  results: FlowResult[];
  summary: AgentSummary;
}

function addDifference(list: Difference[], code: string, message: string, options: Partial<Difference> = {}) {
  list.push({ code, message, ...options });
}

function asObject(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function asArray(value: unknown): unknown[] { return Array.isArray(value) ? value : []; }
function asNumber(value: unknown): number { return Number(value); }

export async function loadAgentArtifacts(directory: string): Promise<AgentArtifacts> {
  const plan = JSON.parse(await fs.readFile(path.join(directory, "plan.json"), "utf8")) as PlanFile;
  const summary = JSON.parse(await fs.readFile(path.join(directory, "summary.json"), "utf8")) as AgentSummary;
  const results = await readResults(path.join(directory, "results.jsonl"));
  return { directory, plan, results, summary };
}

export function validateArtifacts(runId: string, artifacts: AgentArtifacts[]): Difference[] {
  const differences: Difference[] = [];
  if (artifacts.length < 2) addDifference(differences, "AGENT_MISSING", "至少需要两台代理的结果目录");
  const agentIds = new Set<string>();
  const identities = new Map<string, string>();
  for (const artifact of artifacts) {
    const agentId = artifact.plan.agentId;
    if (artifact.plan.runId !== runId || artifact.summary.runId !== runId) {
      addDifference(differences, "RUN_ID_MISMATCH", "计划或摘要的 runId 与中心不一致", { agentId, expected: runId, actual: `${artifact.plan.runId}/${artifact.summary.runId}` });
    }
    if (agentIds.has(agentId)) addDifference(differences, "AGENT_ID_DUPLICATE", "两个结果目录使用了相同 agentId", { agentId });
    agentIds.add(agentId);
    const planIds = new Set(artifact.plan.items.map((item) => item.operationId));
    const resultIds = new Set(artifact.results.map((result) => result.operationId));
    for (const item of artifact.plan.items) {
      const itemIdentities = [item.operationId, item.phone, item.uid, ...(item.flowType === "game" ? [item.externalSessionId] : [])];
      for (const identity of itemIdentities) {
        const prior = identities.get(identity);
        if (prior) addDifference(differences, "IDENTITY_COLLISION", "跨代理计划身份发生碰撞", { agentId, operationId: item.operationId, expected: "唯一", actual: prior });
        else identities.set(identity, `${agentId}/${item.operationId}`);
      }
      if (!resultIds.has(item.operationId)) addDifference(differences, "PLAN_INCOMPLETE", "计划项没有对应执行结果", { agentId, operationId: item.operationId });
    }
    for (const result of artifact.results) {
      if (!planIds.has(result.operationId)) addDifference(differences, "UNPLANNED_RESULT", "执行结果无法对应到计划项", { agentId, actual: result.operationId });
    }
  }
  if (artifacts.length >= 2) {
    const start = Math.max(...artifacts.map((item) => Date.parse(item.summary.startedAt)));
    const end = Math.min(...artifacts.map((item) => Date.parse(item.summary.endedAt)));
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) addDifference(differences, "NO_RUNTIME_OVERLAP", "两台代理的实际运行时间没有重叠");
  }
  return differences;
}

async function query(client: PlatformClient, endpoint: string, differences: Difference[], item: PlanItem, agentId: string): Promise<unknown> {
  const result = await client.step("verify", "GET", endpoint);
  if (!stepSucceeded(result)) {
    addDifference(differences, "VERIFY_QUERY_FAILED", `核账查询失败：${endpoint}`, { agentId, operationId: item.operationId, expected: "2xx", actual: result.kind === "http" ? result.status : result.kind });
    return null;
  }
  return result.response;
}

export function verifyMember(item: PlanItem, value: unknown, differences: Difference[], agentId: string): number | null {
  const members = asArray(value);
  if (members.length !== 1) {
    addDifference(differences, "MEMBER_COUNT_MISMATCH", "会员数量不等于 1", { agentId, operationId: item.operationId, expected: 1, actual: members.length });
    return null;
  }
  const member = asObject(members[0]);
  if (!member) return null;
  if (member.phone !== item.phone) addDifference(differences, "MEMBER_PHONE_MISMATCH", "会员手机号不一致", { agentId, operationId: item.operationId, expected: item.phone, actual: member.phone });
  if (member.name !== item.memberName) addDifference(differences, "MEMBER_NAME_MISMATCH", "会员名称不一致", { agentId, operationId: item.operationId, expected: item.memberName, actual: member.name });
  const id = asNumber(member.id);
  return Number.isInteger(id) && id > 0 ? id : null;
}

export function verifyWristband(item: PlanItem, value: unknown, memberId: number | null, differences: Difference[], agentId: string) {
  const wristband = asObject(value);
  if (!wristband) return addDifference(differences, "WRISTBAND_MISSING", "手环不存在", { agentId, operationId: item.operationId });
  const expectedStatus = item.flowType === "game" ? "ACTIVE" : "READY";
  const fields: Array<[string, string, unknown, unknown]> = [
    ["UID", "UID", item.uid, wristband.uid], ["STATUS", "状态", expectedStatus, wristband.status], ["DURATION", "充时时长", item.durationMinutes, asNumber(wristband.durationMinutes)],
  ];
  if (memberId !== null) fields.push(["BINDING", "绑定会员", memberId, asNumber(wristband.memberId)]);
  for (const [code, label, expected, actual] of fields) {
    if (expected !== actual) addDifference(differences, `WRISTBAND_${code}_MISMATCH`, `手环${label}不一致`, { agentId, operationId: item.operationId, expected, actual });
  }
}

export function verifyGame(item: Extract<PlanItem, { flowType: "game" }>, value: unknown, differences: Difference[], agentId: string) {
  const info = asObject(value);
  if (!info) return;
  const points = asObject(info.points);
  if (asNumber(points?.total) !== item.rawScore) addDifference(differences, "POINTS_MISMATCH", "会员积分与确定性预期不一致", { agentId, operationId: item.operationId, expected: item.rawScore, actual: points?.total });
  const plays = asArray(info.recentPlays);
  if (plays.length !== 1) addDifference(differences, "GAME_COUNT_MISMATCH", "独占测试会员的游戏记录数不等于 1", { agentId, operationId: item.operationId, expected: 1, actual: plays.length });
  const play = asObject(plays[0]);
  if (!play) return addDifference(differences, "GAME_MISSING", "游戏记录不存在", { agentId, operationId: item.operationId });
  const expectedFields: Record<string, unknown> = { status: "COMPLETED", rawScore: item.rawScore, pointsAwarded: item.rawScore, gameId: "concurrency-test-game", deviceId: item.deviceId };
  for (const [name, expected] of Object.entries(expectedFields)) {
    if (play[name] !== expected) addDifference(differences, `GAME_${name.toUpperCase()}_MISMATCH`, `游戏字段 ${name} 不一致`, { agentId, operationId: item.operationId, expected, actual: play[name] });
  }
}

export function verifyGlobalGameRecord(item: Extract<PlanItem, { flowType: "game" }>, memberId: number | null, value: unknown, differences: Difference[], agentId: string) {
  const plays = asArray(value).map(asObject).filter((play): play is Record<string, unknown> => play !== null);
  const matches = plays.filter((play) => play.externalSessionId === item.externalSessionId && play.deviceId === item.deviceId);
  if (matches.length !== 1) {
    addDifference(differences, "GAME_SESSION_COUNT_MISMATCH", "外部会话对应的游戏记录数不等于 1", { agentId, operationId: item.operationId, expected: 1, actual: matches.length });
    return;
  }
  const play = matches[0]!;
  if (memberId !== null && asNumber(play.memberId) !== memberId) addDifference(differences, "GAME_MEMBER_MISMATCH", "游戏记录关联会员不一致", { agentId, operationId: item.operationId, expected: memberId, actual: play.memberId });
  if (play.uid !== item.uid) addDifference(differences, "GAME_WRISTBAND_MISMATCH", "游戏记录关联手环不一致", { agentId, operationId: item.operationId, expected: item.uid, actual: play.uid });
  if (play.roomId !== item.roomId) addDifference(differences, "GAME_ROOM_MISMATCH", "游戏记录房间不一致", { agentId, operationId: item.operationId, expected: item.roomId, actual: play.roomId });
}

export function percentile(samples: number[], ratio: number): number {
  if (samples.length === 0) return 0;
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1)]!;
}

export function classifyFinalState(result: FlowResult | undefined, differencesBefore: number, differencesAfter: number): "confirmed" | "uncertain-committed" | "failed" | "unattempted" {
  if (!result) return "unattempted";
  if (differencesAfter !== differencesBefore) return "failed";
  if (result.success) return "confirmed";
  return responseWasUncertain(result) ? "uncertain-committed" : "failed";
}

export function responseWasUncertain(result: FlowResult): boolean {
  return result.steps.some((step) => step.kind === "timeout" || step.kind === "network");
}

export async function scanCenterLog(file: string): Promise<string[]> {
  const content = await fs.readFile(file, "utf8").catch(() => "");
  return content.split(/\r?\n/).filter((line) => /SQLITE_BUSY|database is locked|\bHTTP\s+5\d\d\b|status[=: ]+5\d\d|exited|exit code/i.test(line)).slice(0, 500);
}

export async function verifyRun(config: VerifyConfig): Promise<VerificationReport> {
  const connection = JSON.parse(await fs.readFile(config.connectionFile, "utf8")) as { runId: string; platformBaseUrl: string; centerLogPath: string; runRoot?: string };
  const artifacts: AgentArtifacts[] = [];
  const inputDifferences: Difference[] = [];
  for (const directory of config.agentDirectories) {
    try { artifacts.push(await loadAgentArtifacts(directory)); }
    catch (error) { addDifference(inputDifferences, "AGENT_ARTIFACT_MISSING", `无法读取代理结果目录：${directory}（${error instanceof Error ? error.message : String(error)}）`); }
  }
  const differences = [...inputDifferences, ...validateArtifacts(connection.runId, artifacts)];
  const client = new PlatformClient(connection.platformBaseUrl, config.requestTimeoutMs);
  const gamePlanCount = artifacts.reduce((count, artifact) => {
    const results = new Map(artifact.results.map((result) => [result.operationId, result]));
    return count + artifact.plan.items.filter((item) => {
      const result = results.get(item.operationId);
      return item.flowType === "game" && result !== undefined && (result.success || responseWasUncertain(result));
    }).length;
  }, 0);
  let allGamePlays: unknown = [];
  if (gamePlanCount > 200) {
    addDifference(differences, "VERIFY_API_LIMIT", "本次游戏计划超过公开列表接口的 200 条精确核账上限", { expected: "<= 200", actual: gamePlanCount });
  } else if (gamePlanCount > 0) {
    const gameList = await client.step("verifyAllGames", "GET", "/api/game-plays");
    if (stepSucceeded(gameList)) allGamePlays = gameList.response;
    else addDifference(differences, "VERIFY_QUERY_FAILED", "核账查询失败：/api/game-plays", { expected: "2xx", actual: gameList.kind === "http" ? gameList.status : gameList.kind });
  }
  let uncertainButCommitted = 0;
  for (const artifact of artifacts) {
    const results = new Map(artifact.results.map((result) => [result.operationId, result]));
    for (const item of artifact.plan.items) {
      const result = results.get(item.operationId);
      if (!result) continue;
      if (!result.success && !responseWasUncertain(result)) {
        addDifference(differences, "REQUEST_FAILED", result.error ?? "代理流程收到明确失败响应", { agentId: artifact.plan.agentId, operationId: item.operationId });
        continue;
      }
      const itemDifferences: Difference[] = [];
      const memberId = verifyMember(item, await query(client, `/api/members?phone=${encodeURIComponent(item.phone)}`, itemDifferences, item, artifact.plan.agentId), itemDifferences, artifact.plan.agentId);
      verifyWristband(item, await query(client, `/api/wristbands/${encodeURIComponent(item.uid)}`, itemDifferences, item, artifact.plan.agentId), memberId, itemDifferences, artifact.plan.agentId);
      if (item.flowType === "game") {
        verifyGame(item, await query(client, `/api/player-info?phone=${encodeURIComponent(item.phone)}`, itemDifferences, item, artifact.plan.agentId), itemDifferences, artifact.plan.agentId);
        verifyGlobalGameRecord(item, memberId, allGamePlays, itemDifferences, artifact.plan.agentId);
      }
      if (result.success) differences.push(...itemDifferences);
      else if (classifyFinalState(result, 0, itemDifferences.length) === "uncertain-committed") uncertainButCommitted++;
      else addDifference(differences, "REQUEST_FAILED", result.error ?? "代理流程失败且最终状态不完整", { agentId: artifact.plan.agentId, operationId: item.operationId });
    }
  }
  const logErrors = await scanCenterLog(connection.centerLogPath);
  for (const line of logErrors) addDifference(differences, "CENTER_LOG_ERROR", "中心日志发现异常证据", { actual: line });
  const agents = artifacts.map((item) => item.summary);
  const overlapStart = agents.length ? Math.max(...agents.map((item) => Date.parse(item.startedAt))) : 0;
  const overlapEnd = agents.length ? Math.min(...agents.map((item) => Date.parse(item.endedAt))) : 0;
  const samples = agents.flatMap((item) => item.durationSamplesMs).filter(Number.isFinite);
  const invalidCodes = new Set(["AGENT_MISSING", "AGENT_ARTIFACT_MISSING", "RUN_ID_MISMATCH", "AGENT_ID_DUPLICATE", "IDENTITY_COLLISION", "NO_RUNTIME_OVERLAP"]);
  const invalid = differences.some((item) => invalidCodes.has(item.code));
  const dataIntegrityPassed = !differences.some((item) => /^(MEMBER_|WRISTBAND_|GAME_|POINTS_|VERIFY_)/.test(item.code));
  const transportFailed = agents.some((item) => item.failed > 0 || item.incomplete > 0 || item.http5xx > 0 || item.timeouts > 0 || item.networkErrors > 0);
  const p95Ms = percentile(samples, 0.95);
  const elapsedSeconds = agents.length ? Math.max(0.001, (Math.max(...agents.map((item) => Date.parse(item.endedAt))) - Math.min(...agents.map((item) => Date.parse(item.startedAt)))) / 1000) : 1;
  const flowCounts = { registration: { planned: 0, attempted: 0, succeeded: 0, failed: 0 }, game: { planned: 0, attempted: 0, succeeded: 0, failed: 0 } };
  for (const artifact of artifacts) {
    for (const item of artifact.plan.items) flowCounts[item.flowType].planned++;
    const typeByOperation = new Map(artifact.plan.items.map((item) => [item.operationId, item.flowType]));
    for (const result of artifact.results) {
      const type = typeByOperation.get(result.operationId);
      if (!type) continue;
      flowCounts[type].attempted++;
      if (result.success) flowCounts[type].succeeded++; else flowCounts[type].failed++;
    }
  }
  return {
    formatVersion: FORMAT_VERSION, runId: connection.runId, generatedAt: new Date().toISOString(), conclusion: invalid ? "INVALID" : dataIntegrityPassed && !transportFailed && logErrors.length === 0 ? "PASSED" : "FAILED", dataIntegrityPassed, agents,
    overlapSeconds: Math.max(0, (overlapEnd - overlapStart) / 1000),
    counts: { planned: agents.reduce((n, item) => n + item.planned, 0), attempted: agents.reduce((n, item) => n + item.attempted, 0), succeeded: agents.reduce((n, item) => n + item.succeeded, 0), failed: agents.reduce((n, item) => n + item.failed, 0), incomplete: agents.reduce((n, item) => n + item.incomplete, 0), uncertainButCommitted },
    performance: { requests: samples.length, requestsPerSecond: Number((samples.length / elapsedSeconds).toFixed(2)), p50Ms: percentile(samples, .5), p95Ms, p99Ms: percentile(samples, .99), warning: p95Ms > config.performanceWarningP95Ms },
    sqliteLockErrors: logErrors, differences,
    coverageBoundary: ["直接 API 并发负载，不覆盖自助注册端和游戏端 UI。", "不覆盖游戏本地后端、房间 IP、控制器、地砖和真实刷卡器。", "测试备份 root override 只验证隔离配置，不代表真实异盘备份验收。"],
    dataDirectories: [connection.runRoot ?? path.dirname(connection.centerLogPath), ...artifacts.map((item) => item.directory), config.outputDirectory],
    flowCounts,
  };
}

export function renderChineseMarkdown(report: VerificationReport): string {
  const verdict = report.conclusion === "PASSED" ? "通过" : report.conclusion === "FAILED" ? "失败" : "无效";
  return ["# 多点并发验收报告", "", `- 运行编号：${report.runId}`, `- 结论：**${verdict}**`, `- 两机重叠运行：${report.overlapSeconds.toFixed(1)} 秒`, `- 计划/尝试/成功/失败/未完成：${report.counts.planned}/${report.counts.attempted}/${report.counts.succeeded}/${report.counts.failed}/${report.counts.incomplete}`, `- 注册流（计划/尝试/成功/失败）：${report.flowCounts.registration.planned}/${report.flowCounts.registration.attempted}/${report.flowCounts.registration.succeeded}/${report.flowCounts.registration.failed}`, `- 游戏流（计划/尝试/成功/失败）：${report.flowCounts.game.planned}/${report.flowCounts.game.attempted}/${report.flowCounts.game.succeeded}/${report.flowCounts.game.failed}`, `- 请求无响应但最终已提交：${report.counts.uncertainButCommitted}`, "", "## 代理结果", "", ...report.agents.map((item) => `- ${item.agentId}：${item.profile}，${item.startedAt} 至 ${item.endedAt}，平台 ${item.platformBaseUrl}；5xx ${item.http5xx}，超时 ${item.timeouts}，连接失败 ${item.networkErrors}`), "", "## 数据正确性", "", report.dataIntegrityPassed ? "会员、手环、游戏记录和积分逐项核对通过。" : `发现 ${report.differences.length} 项差异。`, "", "## 性能观察（不参与数据正确性结论）", "", `请求数 ${report.performance.requests}，吞吐量 ${report.performance.requestsPerSecond} 请求/秒，p50 ${report.performance.p50Ms}ms，p95 ${report.performance.p95Ms}ms，p99 ${report.performance.p99Ms}ms。${report.performance.warning ? "存在性能告警。" : "未触发性能告警。"}`, "", "## 差异", "", ...(report.differences.length ? report.differences.map((item) => `- [${item.code}] ${item.agentId ?? "-"}/${item.operationId ?? "-"}：${item.message}`) : ["- 无"]), "", "## 数据目录", "", ...report.dataDirectories.map((item) => `- ${item}`), "", "## 覆盖边界", "", ...report.coverageBoundary.map((item) => `- ${item}`), ""].join("\n");
}

export async function writeVerificationReport(report: VerificationReport, outputDirectory: string): Promise<{ json: string; markdown: string }> {
  await fs.mkdir(outputDirectory, { recursive: true });
  const json = path.join(outputDirectory, "验收报告.json");
  const markdown = path.join(outputDirectory, "验收报告.md");
  await fs.writeFile(json, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  await fs.writeFile(markdown, renderChineseMarkdown(report), "utf8");
  return { json, markdown };
}
