import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const here = fileURLToPath(import.meta.url);
const members = readFileSync(resolve(here, "../views/MembersView.vue"), "utf8");
const records = readFileSync(resolve(here, "../views/RecordsView.vue"), "utf8");
const kiosk = readFileSync(resolve(here, "../../../registration-kiosk/src/App.vue"), "utf8");
const deduction = readFileSync(resolve(here, "../components/MemberPointsDialog.vue"), "utf8");
const levels = readFileSync(resolve(here, "../components/MemberLevelsSettings.vue"), "utf8");
const settings = readFileSync(resolve(here, "../views/SettingsView.vue"), "utf8");

describe("authoritative points projections", () => {
  it("displays the server-derived level without local scoring or manual assignment", () => {
    expect(members).toContain('data-testid="admin-member-level"');
    expect(members).toContain('data-testid="admin-member-detail-level"');
    expect(members).toContain('levelName: item.levelName ?? null');
    expect(members).toContain('member.levelName || text("memberLevelUnrated")');
    expect(kiosk).toContain('data-testid="kiosk-info-level"');
    expect(kiosk).toContain("playerInfoState.info.profile.levelName || text('memberLevelUnrated')");
    expect(kiosk).not.toContain("/api/member-levels");
  });

  it("scopes level configuration by role, validates inclusive intervals and guards busy modal actions", () => {
    expect(settings).toContain('canManageAccounts.value ? [{ id: "levels"');
    expect(settings).toContain(':can-delete="isFactory"');
    expect(levels).toContain('v-if="canDelete"');
    expect(levels).toContain('!props.canDelete');
    expect(levels).toContain('name.length > 30');
    expect(levels).toContain('Number.isSafeInteger(form.minPoints)');
    expect(levels).toContain('form.minPoints > form.maxPoints');
    expect(levels).toContain('row.minPoints <= form.maxPoints && row.maxPoints >= form.minPoints');
    expect(levels).toContain('if (busy.value) return');
    expect(levels).toContain('if (!busy.value)');
    expect(levels).toContain('data-testid="member-level-delete-confirm"');
    expect(levels).toContain("'memberLevelDelete'");
    expect(levels).not.toContain("'memberDeleteAction'");
    expect(levels).toContain("'memberLevelSave'");
    expect(levels).not.toMatch(/window\.(alert|confirm|prompt)/);
  });

  it("separates available and ranking points with a compatible read-only kiosk projection", () => {
    expect(members).toContain('data-testid="admin-member-remaining-points"');
    expect(members).toContain('data-testid="admin-member-ranking-points"');
    expect(members).toContain("item.remainingPoints ?? item.pointsTotal ?? 0");
    expect(members).toContain("item.rankingPoints ?? item.pointsTotal ?? 0");
    expect(members).toContain('data-testid="admin-member-point-history-button"');
    expect(members).toContain("record.beforePoints");
    expect(members).toContain("record.afterPoints");
    expect(members).toContain("record.operatorUsername");
    expect(kiosk).toContain("playerInfoState.info.points.remaining ?? playerInfoState.info.points.total");
    expect(kiosk).toContain("playerInfoState.info.points.ranking ?? playerInfoState.info.points.total");
    expect(kiosk).not.toContain("point-deductions");
  });

  it("guards duplicate submissions, retains a lost-response request and validates points/reason", () => {
    expect(deduction).toContain("Number.isSafeInteger(points.value) && points.value > 0");
    expect(deduction).toContain("reason.value.trim().length > 500");
    expect(deduction).toContain("if (expected.value < 0)");
    expect(deduction).toContain("if (busy.value) return");
    expect(deduction).toContain("if (!busy.value) emit");
    expect(deduction).toContain("if (!submitted.value)");
    expect(deduction).toContain("requestId: crypto.randomUUID()");
    expect(deduction).toContain("body: submitted.value");
    expect(deduction).toContain("cause.status >= 400 && cause.status < 500");
    expect(deduction).toContain(':disabled="busy || !!submitted"');
    expect(deduction).not.toMatch(/window\.(alert|confirm|prompt)/);
  });

  it("renders platform member totals and shared rank without local calculation", () => {
    expect(members).toContain('data-testid="admin-member-points"');
    expect(members).toContain('data-testid="admin-member-rank"');
    expect(members).toContain("item.pointsTotal");
    expect(members).toContain("item.rank");
    expect(members).toContain('@keydown.enter="loadMembers"');
  });

  it("distinguishes raw score, awarded points, policy and terminal reason", () => {
    expect(records).toContain('data-testid="admin-play-raw-score"');
    expect(records).toContain('data-testid="admin-play-points"');
    expect(records).toContain("item.scoringPolicy");
    expect(records).toContain("item.terminationReason");
  });

  it("uses real binding and charge records without the obsolete game data tab", () => {
    expect(records).toContain('/api/records/wristband-bindings');
    expect(records).toContain('/api/records/wristband-charges');
    expect(records).toContain('data-testid="admin-binding-records"');
    expect(records).toContain('data-testid="admin-charge-records"');
    expect(records).not.toContain('id: "games"');
    expect(records).not.toContain("gameConfigs");
    expect(records).not.toContain("来自 SQLite");
    expect(records).not.toContain("dataSourceLabel");
  });

  it("routes each visible operational export to a fixed complete dataset", () => {
    expect(members).not.toContain('exportData("members", operator.id)');
    expect(members).not.toContain('data-testid="admin-members-export"');
    expect(records).toContain('members: "members", transactions: "wristband-charges", plays: "game-plays", cards: null');
    expect(records).toContain('data-testid="admin-records-export"');
    expect(records).toContain('canUseOperatorCapability(operatorSession.current.value, "exportData")');
    expect(records).not.toContain('class="export-note"');
  });

  it("gives acceptance stable Player Info business selectors", () => {
    expect(kiosk).toContain('data-testid="kiosk-info-points-total"');
    expect(kiosk).toContain('data-testid="kiosk-info-rank"');
    expect(kiosk).toContain('data-testid="kiosk-info-play-raw-score"');
    expect(kiosk).toContain('data-testid="kiosk-info-play-points"');
  });
});
