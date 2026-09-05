import type { StepClient } from "./agent.js";
import type { FlowResult, PlanFile, PlanItem, ScheduleMetrics, StepResult } from "./types.js";

export interface ScheduleClock {
  now(): number;
  sleep(ms: number): Promise<void>;
}
const realClock: ScheduleClock = { now: () => performance.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)) };

export async function runScheduled(
  plan: PlanFile, client: StepClient,
  execute: (item: PlanItem, client: StepClient) => Promise<FlowResult>,
  save: (result: FlowResult) => Promise<void>,
  progress?: (message: string) => void,
  clock: ScheduleClock = realClock,
) {
  const schedule = plan.schedule!;
  const started = clock.now();
  const elapsed = () => Math.max(0, clock.now() - started);
  const deadline = schedule.durationMs + schedule.graceMs;
  const pending = new Map<string, number>();
  for (const item of plan.items) for (const s of item.scheduledSteps!) pending.set(`${item.operationId}/${s.name}`, s.atMs);
  for (const q of schedule.queries) pending.set(q.name, q.atMs);
  const plannedSteps = pending.size;
  const delays: number[] = [];
  const samples: ScheduleMetrics["samples"] = [];
  let inFlight = 0, completed = 0, skipped = 0, maxPending = 0, pendingAtDeadline = 0;
  const dueCount = () => [...pending.values()].filter(time => time <= elapsed()).length;
  const sample = () => {
    const due = dueCount(); maxPending = Math.max(maxPending, due);
    if (elapsed() >= deadline) pendingAtDeadline = Math.max(pendingAtDeadline, due);
    samples.push({ elapsedMs: Math.round(elapsed()), pending: due, inFlight, completed });
  };
  const waitUntil = async (time: number) => {
    while (elapsed() < Math.min(time, deadline)) {
      await clock.sleep(Math.max(1, Math.min(time, deadline) - elapsed()));
    }
    if (elapsed() >= deadline) throw new Error("超过有限收尾时间，剩余步骤未执行");
  };
  const invoke = async (key: string, atMs: number, notBefore: number, args: Parameters<StepClient["step"]>) => {
    await waitUntil(Math.max(atMs, notBefore));
    const actualOffsetMs = elapsed();
    maxPending = Math.max(maxPending, dueCount());
    pending.delete(key); inFlight++;
    const delay = Math.max(0, actualOffsetMs - atMs); delays.push(delay);
    try {
      const result = await client.step(...args);
      return { ...result, plannedOffsetMs: atMs, actualOffsetMs, startDelayMs: delay };
    } finally { inFlight--; completed++; }
  };
  const succeeded = new Set<string>();
  const groups = new Map<string, PlanItem[]>();
  for (const item of plan.items) {
    const key = `${item.flowType}/${item.worker}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(item);
  }
  let finished = false;
  const monitor = (async () => {
    let nextProgress = 0;
    while (!finished) {
      sample();
      if (elapsed() >= nextProgress) {
        progress?.(`已运行 ${(elapsed() / 1000).toFixed(0)} 秒；已结束请求 ${completed}/${plannedSteps}；到点待执行 ${dueCount()}；正在请求 ${inFlight}`);
        nextProgress += 30000;
      }
      await clock.sleep(Math.min(1000, Math.max(1, deadline - elapsed())));
      if (elapsed() >= deadline) { sample(); break; }
    }
  })();
  const queryResults: StepResult[] = [];
  try {
    await Promise.all([
      ...[...groups.values()].map(async items => {
        for (const item of items) {
          if (elapsed() >= deadline) break;
          if (item.dependsOn && !succeeded.has(item.dependsOn)) {
            for (const s of item.scheduledSteps!) { pending.delete(`${item.operationId}/${s.name}`); skipped++; }
            await save({ formatVersion: plan.formatVersion, operationId: item.operationId, flowType: item.flowType,
              startedAt: new Date().toISOString(), endedAt: new Date().toISOString(), success: false, steps: [], error: "前序会员或手环准备失败，依赖流程受阻" });
            continue;
          }
          let gameStartedAt: number | undefined;
          const wrapped: StepClient = { step: async (...args) => {
            const step = item.scheduledSteps!.find(s => s.name === args[0]);
            if (!step) throw new Error(`步骤不在原计划中：${args[0]}`);
            const value = await invoke(`${item.operationId}/${step.name}`, step.atMs,
              step.name === "settleGame" && gameStartedAt !== undefined ? gameStartedAt + item.playDurationMs! : 0, args);
            if (step.name === "startGame") gameStartedAt = elapsed();
            return value;
          } };
          const result = await execute(item, wrapped);
          if (result.success) succeeded.add(item.operationId);
          // 失败后剩余业务步骤属于跳过；截止时则保留为到期未执行。
          if (elapsed() < deadline) for (const s of item.scheduledSteps!) {
            if (pending.delete(`${item.operationId}/${s.name}`)) skipped++;
          }
          await save(result);
        }
      }),
      (async () => {
        for (const q of schedule.queries) {
          if (elapsed() >= deadline) break;
          try { queryResults.push(await invoke(q.name, q.atMs, 0, [q.name, "GET", q.path])); }
          catch { break; }
        }
      })(),
    ]);
    // 即使计划最后几条提前完成，也运行到配置时长，不冒充持续运行。
    if (elapsed() < schedule.durationMs) await clock.sleep(schedule.durationMs - elapsed());
  } finally { finished = true; await monitor; sample(); }
  const sorted = [...delays].sort((a, b) => a - b);
  const p95 = sorted[Math.max(0, Math.ceil(sorted.length * .95) - 1)] ?? 0;
  const metrics: ScheduleMetrics = { durationMs: schedule.durationMs, actualDurationMs: elapsed(), graceMs: schedule.graceMs,
    lagThresholdMs: schedule.lagThresholdMs, p95StartDelayMs: p95, maxStartDelayMs: sorted.at(-1) ?? 0,
    maxPending, pendingAtDeadline: Math.max(pendingAtDeadline, pending.size), skippedSteps: skipped,
    completedSteps: completed, plannedSteps, samples,
    executionPassed: p95 <= schedule.lagThresholdMs && pending.size === 0 && skipped === 0 && elapsed() <= deadline + 5000 };
  return { metrics, queryResults };
}
