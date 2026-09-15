import type { OperatorProfile } from "@ledgame/platform-api-client";

export type OperatorCapability =
  | "operationsView"
  | "memberManage"
  | "featureSettings"
  | "deleteMember"
  | "clearWristbandBalance"
  | "renameRoom"
  | "exportData";

const MATRIX: Record<OperatorProfile["accountType"], ReadonlySet<OperatorCapability>> = {
  FACTORY_ADMIN: new Set([
    "operationsView", "memberManage", "featureSettings", "deleteMember", "clearWristbandBalance", "renameRoom", "exportData",
  ]),
  STORE_MANAGER: new Set([
    "operationsView", "memberManage", "featureSettings", "clearWristbandBalance", "renameRoom", "exportData",
  ]),
  CLERK: new Set(["memberManage", "featureSettings", "clearWristbandBalance"]),
};

export function canUseOperatorCapability(
  operator: OperatorProfile | null | undefined,
  capability: OperatorCapability,
): boolean {
  if (!operator) return false;
  return MATRIX[operator.accountType].has(capability);
}
