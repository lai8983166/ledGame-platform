import type { OperatorProfile } from "@ledgame/platform-api-client";

export type OperatorCapability =
  | "operationsView"
  | "deleteMember"
  | "clearWristbandBalance"
  | "renameRoom"
  | "exportData";

const MATRIX: Record<OperatorProfile["accountType"], ReadonlySet<OperatorCapability>> = {
  FACTORY_ADMIN: new Set([
    "operationsView", "deleteMember", "clearWristbandBalance", "renameRoom", "exportData",
  ]),
  STORE_MANAGER: new Set([
    "operationsView", "clearWristbandBalance", "renameRoom", "exportData",
  ]),
  CLERK: new Set(["clearWristbandBalance"]),
};

export function canUseOperatorCapability(
  operator: OperatorProfile | null | undefined,
  capability: OperatorCapability,
): boolean {
  if (!operator) return false;
  return MATRIX[operator.accountType].has(capability);
}
