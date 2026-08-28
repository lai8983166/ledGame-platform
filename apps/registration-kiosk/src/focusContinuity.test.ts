import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { blurActiveEditableElement, isEditableEventTarget } from "./focusContinuity";

const app = readFileSync(new URL("./App.vue", import.meta.url), "utf8");

describe("registration kiosk focus continuity", () => {
  it("blurs editable elements when the soft keyboard closes", () => {
    const input = { matches: (selector: string) => selector.includes("input"), blur: vi.fn() };
    expect(blurActiveEditableElement(input)).toBe(true);
    expect(input.blur).toHaveBeenCalledOnce();
  });

  it("leaves non-editable elements alone", () => {
    const button = { matches: () => false, blur: vi.fn() };
    expect(blurActiveEditableElement(button)).toBe(false);
    expect(button.blur).not.toHaveBeenCalled();
  });

  it("recognizes native and contenteditable event targets", () => {
    expect(isEditableEventTarget({ matches: (selector: string) => selector.includes("textarea") })).toBe(true);
    expect(isEditableEventTarget({ matches: () => false })).toBe(false);
  });

  it("reopens the keyboard on pointer interaction and protects editable key events", () => {
    expect(app).toContain('@pointerdown="openInput');
    expect(app).toContain("blurActiveEditableElement(document.activeElement)");
    expect(app).toContain('if (isEditableEventTarget(event.target) && event.key !== "Escape") return;');
  });
});
