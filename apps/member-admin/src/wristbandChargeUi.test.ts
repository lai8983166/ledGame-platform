import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync(new URL("./views/WristbandsView.vue", import.meta.url), "utf8");

describe("explicit member admin wristband charge UI", () => {
  it("starts from a button and never exposes an editable UID input", () => {
    expect(source).toContain('data-testid="admin-charge-start"');
    expect(source).toContain('data-testid="admin-charge-dialog"');
    expect(source).toContain('data-testid="admin-charge-cancel"');
    expect(source).toContain('data-testid="admin-charge-scanned-uid"');
    expect(source).not.toContain('data-testid="admin-charge-uid"');
  });

  it("offers bounded minutes and explicit confirmation only after scanning", () => {
    expect(source).toContain('data-testid="admin-charge-minutes"');
    expect(source).toContain('data-testid="admin-charge-submit"');
    expect(source).toContain("[30,45,60,90,120]");
    expect(source).toContain("beginChargeSubmit");
  });
});
