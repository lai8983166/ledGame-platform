import { createHash } from "node:crypto";
import type { FlowType } from "./types.js";

function digits(seed: string, length: number): string {
  const hex = createHash("sha256").update(seed).digest("hex");
  const value = BigInt(`0x${hex}`) % (10n ** BigInt(length));
  return value.toString().padStart(length, "0");
}

export function identityFor(runId: string, agentId: string, flowType: FlowType, worker: number, iteration: number) {
  const kind = flowType === "registration" ? "R" : "G";
  const seed = `${runId}|${agentId}|${kind}|${worker}|${iteration}`;
  const suffix = `${kind}${worker.toString().padStart(2, "0")}-${iteration.toString().padStart(5, "0")}`;
  return {
    operationId: `${runId}-${agentId}-${suffix}`,
    phone: `9${digits(`${seed}|phone`, 14)}`,
    uid: `8${digits(`${seed}|uid`, 19)}`,
    memberName: `并发测试-${agentId}-${suffix}`,
    deviceId: `CONC-${runId}-${agentId}-GAME-${worker}`,
    roomId: `并发测试-${agentId}-${worker}`,
    externalSessionId: `${runId}-${agentId}-SESSION-${worker}-${iteration}`,
    rawScore: 100 + worker * 10 + iteration,
  };
}
