const EDITABLE_SELECTOR = "input, textarea, select, [contenteditable]:not([contenteditable='false'])";

type MatchableTarget = {
  matches: (selector: string) => boolean;
};

type BlurrableTarget = MatchableTarget & {
  blur: () => void;
};

function isMatchableTarget(target: unknown): target is MatchableTarget {
  return Boolean(target && typeof target === "object" && typeof (target as Partial<MatchableTarget>).matches === "function");
}

export function isEditableEventTarget(target: unknown): boolean {
  return isMatchableTarget(target) && target.matches(EDITABLE_SELECTOR);
}

export function blurActiveEditableElement(target: unknown): boolean {
  if (!isEditableEventTarget(target) || typeof (target as Partial<BlurrableTarget>).blur !== "function") return false;
  (target as BlurrableTarget).blur();
  return true;
}
