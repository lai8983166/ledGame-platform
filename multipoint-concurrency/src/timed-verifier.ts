import type { AgentArtifacts } from "./verifier.js";
import { PlatformClient, stepSucceeded } from "./platform-client.js";
import { expectedTotals } from "./timed-plan.js";
import type { Difference, ExpectedTotals, PlanItem } from "./types.js";

export async function reconcileTimed(artifacts: AgentArtifacts[], client: PlatformClient, differences: Difference[]) {
  const items = artifacts.flatMap(a => a.plan.items);
  const expected = expectedTotals(items);
  const actual: ExpectedTotals = { members: 0, wristbands: 0, charges: 0, bindings: 0, games: 0, points: 0 };
  const memberIds = new Map<string, number>();
  let uncertainButCommitted = 0;
  const diff = (item: PlanItem, code: string, message: string, wanted?: unknown, found?: unknown) => {
    differences.push({ code, message, operationId: item.operationId, expected: wanted, actual: found });
  };
  const rows = async (item: PlanItem, endpoint: string): Promise<Record<string, unknown>[] | null> => {
    const response = await client.step("verify", "GET", endpoint);
    if (!stepSucceeded(response) || !Array.isArray(response.response)) {
      diff(item, "VERIFY_QUERY_FAILED", `核账查询失败：${endpoint}`, "JSON 数组 / HTTP 2xx", response.status ?? response.kind); return null;
    }
    return response.response as Record<string, unknown>[];
  };
  const fields = (item: PlanItem, row: Record<string, unknown>, values: Record<string, unknown>, prefix: string) => {
    for (const [key, wanted] of Object.entries(values)) if (row[key] !== wanted) diff(item, `${prefix}_FIELD_MISMATCH`, `${key} 与计划不一致`, wanted, row[key]);
  };
  const members = new Map(items.map(i => [i.phone, i]));
  const bands = new Map(items.map(i => [i.uid, i]));
  for (const [phone, item] of members) {
    const list = await rows(item, `/api/members?phone=${phone}`);
    if (!list) continue;
    actual.members += list.length;
    if (list.length !== 1) { diff(item, "MEMBER_COUNT_MISMATCH", "会员数量不符合原计划", 1, list.length); continue; }
    const member = list[0]!;
    memberIds.set(phone, Number(member.id));
    fields(item, member, { phone, name: item.memberName }, "MEMBER");
    const games = items.filter(i => i.phone === phone && i.flowType === "game");
    const points = games.reduce((n, i) => n + (i.flowType === "game" ? i.rawScore : 0), 0);
    actual.points += Number(member.pointsTotal ?? 0);
    fields(item, member, { pointsTotal: points }, "POINTS");
    // 每会员最多两局，按会员过滤后不受全库 200 条限制，也不依赖最近 10 局投影。
    const plays = await rows(item, `/api/game-plays?memberId=${member.id}`);
    if (!plays) continue;
    actual.games += plays.length;
    if (plays.length !== games.length) diff(item, "GAME_COUNT_MISMATCH", "会员游戏数量不符合原计划", games.length, plays.length);
    for (const game of games) {
      if (game.flowType !== "game") continue;
      const matches = plays.filter(p => p.externalSessionId === game.externalSessionId && p.deviceId === game.deviceId);
      if (matches.length !== 1) { diff(game, "GAME_SESSION_COUNT_MISMATCH", "游戏会话缺失或重复", 1, matches.length); continue; }
      fields(game, matches[0]!, { memberId: Number(member.id), uid: game.uid, roomId: game.roomId,
        gameId: "concurrency-test-game", status: "COMPLETED", rawScore: game.rawScore, pointsAwarded: game.rawScore,
        terminationReason: "NATURAL_COMPLETION" }, "GAME");
    }
  }
  for (const [uid, item] of bands) {
    const response = await client.step("verify", "GET", `/api/wristbands/${uid}`);
    if (!stepSucceeded(response) || !response.response || typeof response.response !== "object") {
      diff(item, "WRISTBAND_MISSING", "计划手环未查到", uid, response.status ?? response.kind); continue;
    }
    actual.wristbands++;
    fields(item, response.response as Record<string, unknown>, { uid, memberId: memberIds.get(item.phone),
      status: item.flowType === "game" ? "ACTIVE" : "READY", durationMinutes: item.durationMinutes }, "WRISTBAND");
    const charges = await rows(item, `/api/records/wristband-charges?uid=${uid}`);
    if (charges) {
      actual.charges += charges.length;
      if (charges.length !== 1) diff(item, "WRISTBAND_CHARGE_COUNT_MISMATCH", "充值记录缺失或重复", 1, charges.length);
      for (const charge of charges) fields(item, charge, { uid, durationMinutes: item.durationMinutes, amountCents: item.durationMinutes * 100 }, "WRISTBAND_CHARGE");
    }
    const bindings = await rows(item, `/api/records/wristband-bindings?uid=${uid}`);
    if (bindings) {
      actual.bindings += bindings.length;
      if (bindings.length !== 1) diff(item, "WRISTBAND_BINDING_COUNT_MISMATCH", "绑定记录缺失或重复", 1, bindings.length);
      for (const binding of bindings) fields(item, binding, { uid, memberId: memberIds.get(item.phone) }, "WRISTBAND_BINDING");
    }
  }
  for (const artifact of artifacts) {
    const resultMap = new Map(artifact.results.map(r => [r.operationId, r]));
    for (const item of artifact.plan.items) {
      const result = resultMap.get(item.operationId);
      if (!result || result.success) continue;
      const relatedIds = new Set(items.filter(i => i.phone === item.phone).map(i => i.operationId));
      const relatedDifference = differences.some(d => d.operationId && relatedIds.has(d.operationId));
      if (result.steps.some(s => s.kind === "network" || s.kind === "timeout") && !relatedDifference) uncertainButCommitted++;
      differences.push({ code: "REQUEST_FAILED", agentId: artifact.plan.agentId, operationId: item.operationId, message: result.error ?? "流程未完成" });
    }
  }
  for (const key of Object.keys(expected) as Array<keyof ExpectedTotals>) {
    if (expected[key] !== actual[key]) differences.push({ code: "VERIFY_TOTAL_MISMATCH", message: `${key} 预期与实际不一致`, expected: expected[key], actual: actual[key] });
  }
  return { expected, actual, uncertainButCommitted };
}
