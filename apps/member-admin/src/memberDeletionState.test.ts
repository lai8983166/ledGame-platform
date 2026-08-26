import { describe, expect, it, vi } from "vitest";
import {
  cancelMemberDeletion,
  createMemberDeletionState,
  openMemberDeletion,
  submitMemberDeletion,
} from "./memberDeletionState";

const member = { id: 7, name: "测试会员", phone: "13800138000" };

describe("member deletion state", () => {
  it("opens and cancels without issuing a request", async () => {
    const state = createMemberDeletionState();
    const request = vi.fn();
    openMemberDeletion(state, member);
    expect(state).toMatchObject({ status: "confirming", target: member, error: "" });
    cancelMemberDeletion(state);
    expect(state).toMatchObject({ status: "idle", target: null, error: "" });
    expect(request).not.toHaveBeenCalled();
  });

  it("clears the target after a successful delete", async () => {
    const state = createMemberDeletionState();
    openMemberDeletion(state, member);
    await expect(submitMemberDeletion(state, vi.fn().mockResolvedValue({ status: "DELETED" }))).resolves.toBe(true);
    expect(state).toMatchObject({ status: "idle", target: null, error: "" });
  });

  it("retains member context and server reason after a conflict", async () => {
    const state = createMemberDeletionState();
    openMemberDeletion(state, member);
    await expect(submitMemberDeletion(state, vi.fn().mockRejectedValue(new Error("请先解除手环绑定")))).resolves.toBe(false);
    expect(state).toMatchObject({ status: "error", target: member, error: "请先解除手环绑定" });
  });

  it("ignores a response that arrives after cancellation", async () => {
    const state = createMemberDeletionState();
    let resolve!: (value: unknown) => void;
    const pending = new Promise((done) => { resolve = done; });
    openMemberDeletion(state, member);
    const submitted = submitMemberDeletion(state, () => pending);
    cancelMemberDeletion(state);
    resolve({ status: "DELETED" });
    await expect(submitted).resolves.toBe(false);
    expect(state).toMatchObject({ status: "idle", target: null });
  });
});
