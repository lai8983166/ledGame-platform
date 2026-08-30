import type { OperatorProfile } from "@ledgame/platform-api-client";

export type OperatorCapability =
  | "dailyOperations"
  | "settings"
  | "deleteMember"
  | "clearWristbandBalance"
  | "renameRoom"
  | "manageAccounts";

const FACTORY_ONLY = new Set<OperatorCapability>([
  "settings",
  "deleteMember",
  "clearWristbandBalance",
  "renameRoom",
  "manageAccounts",
]);

export function canUseOperatorCapability(
  operator: OperatorProfile | null | undefined,
  capability: OperatorCapability,
): boolean {
  if (!operator) return false;
  return !FACTORY_ONLY.has(capability) || operator.accountType === "FACTORY_ADMIN";
}
