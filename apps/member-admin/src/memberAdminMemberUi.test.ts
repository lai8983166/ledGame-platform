import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync(new URL("./views/MembersView.vue", import.meta.url), "utf8");

describe("member admin deletion UI", () => {
  it("places deletion in the member detail danger area with an application modal", () => {
    expect(source).toContain('data-testid="admin-member-delete"');
    expect(source).toContain('data-testid="admin-member-delete-dialog"');
    expect(source).toContain('data-testid="admin-member-delete-cancel"');
    expect(source).toContain('data-testid="admin-member-delete-confirm"');
    expect(source).toContain('text("memberDeleteDangerBody")');
    expect(source).toContain('text("memberDeleteConsequences")');
    expect(source).not.toContain("window.confirm");
  });

  it("refreshes real members after deletion and retains errors for the modal", () => {
    expect(source).toContain("submitMemberDeletion");
    expect(source).toContain("platformApi.deleteMember");
    expect(source).toContain("await loadMembers()");
  });
});
