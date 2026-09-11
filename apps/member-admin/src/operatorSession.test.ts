import { describe, expect, it } from "vitest";
import { createOperatorSession } from "./operatorSession";
import { canUseOperatorCapability } from "./operatorPolicy";

const factory = { id: 1, username: "admin", displayName: "出厂管理员", accountType: "FACTORY_ADMIN" as const };
const manager = { id: 2, username: "manager", displayName: "店长", accountType: "STORE_MANAGER" as const };
const clerk = { id: 3, username: "counter", displayName: "前台", accountType: "CLERK" as const };

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
    for (const capability of ["operationsView", "deleteMember", "clearWristbandBalance", "renameRoom", "exportData"] as const) {
      expect(canUseOperatorCapability(factory, capability)).toBe(true);
    }
  });

  it("allows managers to operate and export but blocks factory-only deletion and maintenance", () => {
    for (const capability of ["operationsView", "clearWristbandBalance", "renameRoom", "exportData"] as const) {
      expect(canUseOperatorCapability(manager, capability)).toBe(true);
    }
    expect(canUseOperatorCapability(manager, "deleteMember")).toBe(false);
  });

  it("limits clerks to front-desk operations and feature switches", () => {
    expect(canUseOperatorCapability(clerk, "clearWristbandBalance")).toBe(true);
    for (const capability of ["operationsView", "deleteMember", "renameRoom", "exportData"] as const) {
      expect(canUseOperatorCapability(clerk, capability)).toBe(false);
    }
  });
});
