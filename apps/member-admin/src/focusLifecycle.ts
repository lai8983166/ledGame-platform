export type FocusReturnTarget = {
  focus: (options?: FocusOptions) => void;
  isConnected?: boolean;
};

export function captureFocusReturnTarget(activeElement: unknown): FocusReturnTarget | null {
  if (!activeElement || typeof activeElement !== "object") return null;
  const candidate = activeElement as Partial<FocusReturnTarget>;
  return typeof candidate.focus === "function" ? candidate as FocusReturnTarget : null;
}

export function restoreFocusReturnTarget(target: FocusReturnTarget | null): boolean {
  if (!target || target.isConnected === false) return false;
  target.focus({ preventScroll: true });
  return true;
}
