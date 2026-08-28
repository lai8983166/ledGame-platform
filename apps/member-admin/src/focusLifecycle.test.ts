import { describe, expect, it, vi } from "vitest";
import { captureFocusReturnTarget, restoreFocusReturnTarget } from "./focusLifecycle";

describe("overlay focus lifecycle", () => {
  it("captures and restores a connected interactive opener", () => {
    const opener = { focus: vi.fn(), isConnected: true };
    expect(captureFocusReturnTarget(opener)).toBe(opener);

    restoreFocusReturnTarget(opener);

    expect(opener.focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it("does not focus an opener that has left the document", () => {
    const opener = { focus: vi.fn(), isConnected: false };

    restoreFocusReturnTarget(opener);

    expect(opener.focus).not.toHaveBeenCalled();
  });

  it("ignores non-focusable active elements", () => {
    expect(captureFocusReturnTarget({ isConnected: true })).toBeNull();
    expect(captureFocusReturnTarget(null)).toBeNull();
  });
});
