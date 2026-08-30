import { describe, expect, it } from "vitest";
import { createOperatorSession } from "./operatorSession";
import { canUseOperatorCapability } from "./operatorPolicy";

const factory = { id: 1, username: "admin", displayName: "出厂管理员", accountType: "FACTORY_ADMIN" as const };
const operator = { id: 2, username: "counter", displayName: "前台", accountType: "OPERATOR" as const };

describe("in-memory operator session", () => {
  it("starts empty, accepts a login profile and clears it on logout", () => {
    const session = createOperatorSession();
    expect(session.current.value).toBeNull();
    session.login(factory);
    expect(session.current.value).toEqual(factory);
    session.logout();
    expect(session.current.value).toBeNull();
  });

  it("does not restore an account when a new session is created", () => {
    const first = createOperatorSession();
    first.login(factory);
    expect(createOperatorSession().current.value).toBeNull();
  });
});

describe("fixed operator role policy", () => {
  it("allows the factory administrator to use all protected capabilities", () => {
    for (const capability of ["settings", "deleteMember", "clearWristbandBalance", "renameRoom", "manageAccounts"] as const) {
      expect(canUseOperatorCapability(factory, capability)).toBe(true);
    }
  });

  it("keeps daily work available but hides dangerous capabilities from an operator", () => {
    expect(canUseOperatorCapability(operator, "dailyOperations")).toBe(true);
    for (const capability of ["settings", "deleteMember", "clearWristbandBalance", "renameRoom", "manageAccounts"] as const) {
      expect(canUseOperatorCapability(operator, capability)).toBe(false);
    }
  });
});
