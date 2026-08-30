import { describe, expect, it, vi } from "vitest";
import { createOperatorAccountManager } from "./operatorAccountState";

const factory = { id: 1, username: "admin", displayName: "出厂管理员", accountType: "FACTORY_ADMIN" as const, enabled: true, createdAt: "now", updatedAt: "now" };
const operator = { id: 2, username: "counter", displayName: "前台", accountType: "OPERATOR" as const, enabled: true, createdAt: "now", updatedAt: "now" };

describe("operator account manager", () => {
  it("loads, creates, edits, resets password and toggles an operator", async () => {
    const api = {
      listOperatorAccounts: vi.fn().mockResolvedValue([factory]),
      createOperatorAccount: vi.fn().mockResolvedValue(operator),
      updateOperatorAccount: vi.fn().mockResolvedValue({ ...operator, displayName: "收银台" }),
      resetOperatorPassword: vi.fn().mockResolvedValue({ ...operator, displayName: "收银台" }),
      setOperatorEnabled: vi.fn().mockResolvedValue({ ...operator, displayName: "收银台", enabled: false }),
    };
    const state = createOperatorAccountManager(api);
    await state.load();
    await state.create({ username: "counter", displayName: "前台", password: "123456" });
    await state.update(2, { username: "counter", displayName: "收银台" });
    await state.resetPassword(2, "654321");
    await state.setEnabled(2, false);
    expect(state.accounts).toEqual([factory, { ...operator, displayName: "收银台", enabled: false }]);
  });

  it("surfaces stable conflicts and prevents duplicate submissions", async () => {
    let release!: () => void;
    const pending = new Promise<typeof operator>((resolve) => { release = () => resolve(operator); });
    const api = {
      listOperatorAccounts: vi.fn().mockResolvedValue([]),
      createOperatorAccount: vi.fn().mockReturnValue(pending),
      updateOperatorAccount: vi.fn(), resetOperatorPassword: vi.fn(), setOperatorEnabled: vi.fn(),
    };
    const state = createOperatorAccountManager(api);
    const first = state.create({ username: "counter", displayName: "前台", password: "123456" });
    await state.create({ username: "other", displayName: "其他", password: "123456" });
    expect(api.createOperatorAccount).toHaveBeenCalledTimes(1);
    release();
    await first;
  });
});
