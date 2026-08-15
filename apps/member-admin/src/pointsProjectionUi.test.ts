import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const here = fileURLToPath(import.meta.url);
const members = readFileSync(resolve(here, "../views/MembersView.vue"), "utf8");
const records = readFileSync(resolve(here, "../views/RecordsView.vue"), "utf8");
const kiosk = readFileSync(resolve(here, "../../../registration-kiosk/src/App.vue"), "utf8");

describe("authoritative points projections", () => {
  it("renders platform member totals and shared rank without local calculation", () => {
    expect(members).toContain('data-testid="admin-member-points"');
    expect(members).toContain('data-testid="admin-member-rank"');
    expect(members).toContain("item.pointsTotal");
    expect(members).toContain("item.rank");
  });

  it("distinguishes raw score, awarded points, policy and terminal reason", () => {
    expect(records).toContain('data-testid="admin-play-raw-score"');
    expect(records).toContain('data-testid="admin-play-points"');
    expect(records).toContain("item.scoringPolicy");
    expect(records).toContain("item.terminationReason");
  });

  it("gives acceptance stable Player Info business selectors", () => {
    expect(kiosk).toContain('data-testid="kiosk-info-points-total"');
    expect(kiosk).toContain('data-testid="kiosk-info-rank"');
    expect(kiosk).toContain('data-testid="kiosk-info-play-raw-score"');
    expect(kiosk).toContain('data-testid="kiosk-info-play-points"');
  });
});
