import { createHash } from "node:crypto";
import { identityFor } from "./identity.js";
import { FORMAT_VERSION, type AgentConfig, type ExpectedTotals, type PlanFile, type PlanItem } from "./types.js";

// 每个设备独立、可复现的随机序列；不依赖执行速度生成后续目标。
function random(seed: string) {
  let state = createHash("sha256").update(seed).digest().readUInt32LE(0) || 1;
  return (min: number, max: number) => {
    state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
    return min + (state >>> 0) % (max - min + 1);
  };
}

export function expectedTotals(items: PlanItem[]): ExpectedTotals {
  return { members: new Set(items.map(i => i.phone)).size,
    wristbands: new Set(items.map(i => i.uid)).size,
    charges: items.filter(i => i.memberMode !== "replay").length,
    bindings: items.filter(i => i.memberMode !== "replay").length,
    games: items.filter(i => i.flowType === "game").length,
    points: items.reduce((n, i) => n + (i.flowType === "game" ? i.rawScore : 0), 0) };
}

export function buildTimedPlan(config: AgentConfig, now: Date): PlanFile {
  const durationMs = config.scheduledDurationSeconds! * 1000;
  const seed = `${config.runId}|${config.agentId}|store-v1`;
  const items: PlanItem[] = [];
  for (const flowType of ["registration", "game"] as const) {
    const workers = flowType === "registration" ? config.registrationWorkers : config.gameWorkers;
    for (let worker = 1; worker <= workers; worker++) {
      const next = random(`${seed}|${flowType}|${worker}`);
      let atMs = next(0, 8000);
      let previous: PlanItem | undefined;
      for (let iteration = 1; atMs < durationMs; iteration++) {
        const identity = identityFor(config.runId, config.agentId, flowType, worker, iteration);
        const mode = previous && (iteration % 3 === 2) ? (flowType === "game" ? "replay" : "existing") : "new";
        const item: PlanItem = { ...identity, flowType, worker, iteration,
          durationMinutes: config.durationMinutes,
          memberMode: mode,
          ...(mode !== "new" ? { phone: previous!.phone, memberName: previous!.memberName,
            dependsOn: previous!.operationId, ...(mode === "replay" ? { uid: previous!.uid } : {}) } : {}),
          scheduledSteps: [],
        } as PlanItem;
        let cursor = atMs;
        const step = (name: string, delay = next(250, 1500)) => {
          cursor += delay;
          item.scheduledSteps!.push({ name, atMs: cursor });
        };
        if (mode !== "replay") step("charge", 0);
        step("memberLookup", mode === "replay" ? 0 : next(500, 2500));
        if (mode === "new") step("memberCreate", next(2000, 8000));
        if (mode !== "replay") step("bind", next(1000, 4000));
        if (flowType === "registration") step("wristbandQuery");
        else {
          step("activate"); step("startGame", next(1000, 5000));
          item.playDurationMs = next(30000, 180000);
          step("settleGame", item.playDurationMs);
          step("playerInfo");
        }
        // 不安排正常情况下无法在负载时段内完成的游戏；收尾时间仅用于慢请求。
        if (cursor >= durationMs) break;
        items.push(item); previous = item;
        const proposed = cursor + next(flowType === "registration" ? 5000 : 3000, flowType === "registration" ? 35000 : 18000);
        // 每两分钟有短暂集中到店窗口，各设备保留抖动，不使用跨机屏障。
        const burst = Math.ceil(proposed / 120000) * 120000;
        atMs = flowType === "registration" && burst - proposed < 15000 ? burst + next(0, 4000) : proposed;
      }
    }
  }
  const queryRandom = random(`${seed}|queries`);
  const queries: NonNullable<PlanFile["schedule"]>["queries"] = [];
  for (let time = queryRandom(2000, 7000); time < durationMs; time += queryRandom(5000, 15000)) {
    // 使用有界会员查询和排行榜，避免全库列表自身成为压力机的内存瓶颈。
    const member = items[queryRandom(0, items.length - 1)];
    queries.push({ name: `management-${queries.length}`, atMs: time,
      path: queries.length % 2 ? "/api/leaderboard?period=day" : `/api/members?phone=${member?.phone ?? "999999999999999"}` });
  }
  return { formatVersion: FORMAT_VERSION, runId: config.runId, agentId: config.agentId,
    profile: config.profile, platformBaseUrl: config.platformBaseUrl, generatedAt: now.toISOString(), items,
    expected: expectedTotals(items), schedule: { durationMs, graceMs: 30000, lagThresholdMs: 2000,
      seed, registrationWorkers: config.registrationWorkers, gameWorkers: config.gameWorkers, queries } };
}

export function renderPreview(plans: PlanFile[]): string {
  const totals = expectedTotals(plans.flatMap(p => p.items));
  const schedule = plans[0]?.schedule;
  return ["# 并发测试执行预览", "", `运行编号：${plans[0]?.runId}；节点：${plans.map(p => p.agentId).join("、")}`,
    `档位：${plans[0]?.profile}；计划时长：${(schedule?.durationMs ?? 0) / 60000} 分钟；最多收尾：${(schedule?.graceMs ?? 0) / 1000} 秒`,
    `每节点：${schedule?.registrationWorkers ?? "配置决定"} 个注册端、${schedule?.gameWorkers ?? "配置决定"} 个游戏端；随机步骤、重复游玩、穿插查询。`, "",
    "| 新增会员 | 新增手环 | 充值记录 | 绑定记录 | 结算游戏 | 总积分 |",
    "|---:|---:|---:|---:|---:|---:|",
    `| ${totals.members} | ${totals.wristbands} | ${totals.charges} | ${totals.bindings} | ${totals.games} | ${totals.points} |`, "",
    "数量由本次随机计划精确计算；不是运行成功数量。相同 runId、agentId、档位生成相同业务计划。",
    "MB/GB 不作为精确预期；文件大小受数据库页面、WAL、日志和备份影响。",
    "只生成预览不会向会员管理端发送请求。执行时先保存完整计划，再发送业务请求。", ""].join("\n");
}
