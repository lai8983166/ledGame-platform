export interface MemberDeletionTarget {
  id: number;
  name: string;
  phone: string;
}

export interface MemberDeletionState {
  status: "idle" | "confirming" | "submitting" | "error";
  target: MemberDeletionTarget | null;
  error: string;
  revision: number;
}

export function createMemberDeletionState(): MemberDeletionState {
  return { status: "idle", target: null, error: "", revision: 0 };
}

export function openMemberDeletion(state: MemberDeletionState, target: MemberDeletionTarget): void {
  state.revision += 1;
  state.status = "confirming";
  state.target = { ...target };
  state.error = "";
}

export function cancelMemberDeletion(state: MemberDeletionState): void {
  state.revision += 1;
  state.status = "idle";
  state.target = null;
  state.error = "";
}

export async function submitMemberDeletion(
  state: MemberDeletionState,
  remove: (id: number) => Promise<unknown>,
): Promise<boolean> {
  if (!state.target || (state.status !== "confirming" && state.status !== "error")) return false;
  const target = state.target;
  const revision = ++state.revision;
  state.status = "submitting";
  state.error = "";
  try {
    await remove(target.id);
    if (state.revision !== revision || state.status !== "submitting") return false;
    state.status = "idle";
    state.target = null;
    return true;
  } catch (error) {
    if (state.revision !== revision || state.status !== "submitting") return false;
    state.status = "error";
    state.error = error instanceof Error ? error.message : "删除会员失败";
    return false;
  }
}
