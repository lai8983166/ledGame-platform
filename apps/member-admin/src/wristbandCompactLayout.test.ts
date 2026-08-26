import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const view = readFileSync(new URL("./views/WristbandsView.vue", import.meta.url), "utf8");
const style = readFileSync(new URL("./style.css", import.meta.url), "utf8");

describe("compact wristband workbench layout", () => {
  it("groups the operational cards in a stable grid and keeps the table outside it", () => {
    expect(view).toContain('data-testid="admin-wristband-workbench"');
    expect(view).toContain('data-testid="admin-wristband-charge-card"');
    expect(view).toContain('data-testid="admin-wristband-clear-card"');
    expect(view).toContain('data-testid="admin-wristband-flow-card"');
    expect(view).toContain('data-testid="admin-wristband-reclaim-card"');
    expect(view.indexOf("admin-wristband-workbench")).toBeLessThan(view.indexOf("wristband-table-card"));
  });

  it("defines desktop grid density and responsive single-column fallback", () => {
    expect(style).toContain(".wristband-workbench-grid");
    expect(style).toMatch(/\.wristband-workbench-grid\s*\{[^}]*display:\s*grid/);
    expect(style).toMatch(/@media \(max-width:\s*850px\)[\s\S]*\.wristband-workbench-grid[^}]*grid-template-columns:\s*1fr/);
  });
});
