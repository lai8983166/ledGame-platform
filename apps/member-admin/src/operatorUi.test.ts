import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const app = readFileSync(new URL("./App.vue", import.meta.url), "utf8");
const login = readFileSync(new URL("./views/LoginView.vue", import.meta.url), "utf8");
const members = readFileSync(new URL("./views/MembersView.vue", import.meta.url), "utf8");
const wristbands = readFileSync(new URL("./views/WristbandsView.vue", import.meta.url), "utf8");
const rooms = readFileSync(new URL("./views/RoomsView.vue", import.meta.url), "utf8");
const settings = readFileSync(new URL("./views/SettingsView.vue", import.meta.url), "utf8");
const session = readFileSync(new URL("./operatorSession.ts", import.meta.url), "utf8");

describe("member admin login gate", () => {
  it("renders login before the administration shell and returns there on logout", () => {
    expect(app).toContain('v-if="!currentOperator"');
    expect(app).toContain('v-else class="admin-layout"');
    expect(app).toContain("operatorSession.login(profile)");
    expect(app).toContain("operatorSession.logout()");
    expect(app).toContain("currentOperator.displayName");
  });

  it("uses a password form with no recovery action or persistence", () => {
    expect(login).toContain('type="password"');
    expect(login).toContain("platformApi.loginOperator");
    expect(login).toContain("loginFactoryHelp");
    expect(login).not.toContain("忘记密码");
    expect(session).not.toContain("localStorage");
    expect(session).not.toContain("document.cookie");
  });
});

describe("operator UI capability boundaries", () => {
  it("uses one role policy for protected navigation and dangerous actions", () => {
    expect(app).toContain('canUseOperatorCapability(currentOperator.value, "settings")');
    expect(members).toContain('canUseOperatorCapability(operatorSession.current.value, "deleteMember")');
    expect(wristbands).toContain('canUseOperatorCapability(operatorSession.current.value, "clearWristbandBalance")');
    expect(rooms).toContain('canUseOperatorCapability(operatorSession.current.value, "renameRoom")');
  });

  it("keeps daily actions while conditionally hiding only protected controls", () => {
    expect(members).toContain('data-testid="admin-member-create"');
    expect(wristbands).toContain('data-testid="admin-charge-start"');
    expect(wristbands).toContain('data-testid="admin-wristband-reclaim-card"');
    expect(wristbands).toContain('v-if="canClearBalances"');
    expect(rooms).toContain('v-if="canRenameRooms"');
    expect(members).toContain('v-if="canDeleteMembers"');
  });

  it("offers account management without account deletion and protects the factory row", () => {
    expect(settings).toContain('data-testid="operator-account-management"');
    expect(settings).toContain("createOperatorAccountManager");
    expect(settings).toContain("account.accountType === 'OPERATOR'");
    expect(settings).not.toContain("deleteOperatorAccount");
  });
});
