import type { AgentConfig, GamePlanItem, PlanFile, PlanItem, RegistrationPlanItem } from "./types.js";
import { FORMAT_VERSION } from "./types.js";
import { identityFor } from "./identity.js";

export function buildPlan(config: AgentConfig, now = new Date()): PlanFile {
  const items: PlanItem[] = [];
  for (let worker = 1; worker <= config.registrationWorkers; worker += 1) {
    for (let iteration = 1; iteration <= config.iterationsPerWorker; iteration += 1) {
      const id = identityFor(config.runId, config.agentId, "registration", worker, iteration);
      items.push({
        operationId: id.operationId,
        flowType: "registration",
        worker,
        iteration,
        phone: id.phone,
        uid: id.uid,
        memberName: id.memberName,
        durationMinutes: config.durationMinutes,
      } satisfies RegistrationPlanItem);
    }
  }
  for (let worker = 1; worker <= config.gameWorkers; worker += 1) {
    for (let iteration = 1; iteration <= config.iterationsPerWorker; iteration += 1) {
      const id = identityFor(config.runId, config.agentId, "game", worker, iteration);
      items.push({
        operationId: id.operationId,
        flowType: "game",
        worker,
        iteration,
        phone: id.phone,
        uid: id.uid,
        memberName: id.memberName,
        durationMinutes: config.durationMinutes,
        deviceId: id.deviceId,
        roomId: id.roomId,
        externalSessionId: id.externalSessionId,
        rawScore: id.rawScore,
      } satisfies GamePlanItem);
    }
  }
  const keys = new Set<string>();
  for (const item of items) {
    for (const key of [item.operationId, item.phone, item.uid]) {
      if (keys.has(key)) throw new Error(`计划身份发生碰撞：${key}`);
      keys.add(key);
    }
  }
  return {
    formatVersion: FORMAT_VERSION,
    runId: config.runId,
    agentId: config.agentId,
    profile: config.profile,
    platformBaseUrl: config.platformBaseUrl,
    generatedAt: now.toISOString(),
    items,
  };
}
