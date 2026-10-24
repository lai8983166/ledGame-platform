import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
const app = read("./App.vue");
const members = read("./views/MembersView.vue");
const rooms = read("./views/RoomsView.vue");
const dialog = read("./components/MemberCardDialog.vue");
const kiosk = read("../../registration-kiosk/src/App.vue");

describe("合并会员发还卡界面", () => {
  it("只保留两个柜台主操作，不挂载旧手环页面", () => {
    expect(app).not.toContain("import WristbandsView");
    expect(app).not.toContain('{ id: "wristbands"');
    expect(rooms).toContain('data-testid="admin-card-issue"');
    expect(rooms).toContain('data-testid="admin-card-return"');
    expect(members).not.toContain('data-testid="admin-card-issue"');
    expect(members).not.toContain('data-testid="admin-card-return"');
    expect(app).toContain('const activePage = ref<PageId>("rooms")');
    expect(members).toContain('data-testid="admin-member-create"');
    expect(members).toContain('data-testid="admin-member-recharge"');
    expect(members).toContain('data-testid="admin-member-issue"');
    expect(members).not.toContain("会员与手环分离");
    expect(dialog).not.toContain("description=");
  });
  it("会员充值不要求刷手环，发卡只填写分配时长，自助激活文案立即计时", () => {
    const recharge = read("./components/MemberRechargeDialog.vue");
    expect(recharge).toContain('/recharge`');
    expect(recharge).toContain('initialDurationMinutes: minutes.value');
    expect(recharge).toContain('amountCents: amount.value === "" ? null');
    expect(recharge).not.toContain('keydown", onKeydown');
    expect(dialog).toContain("isPrecharged ? 'prechargeMinutes' : 'memberAllocationMinutes'");
    expect(dialog).toContain('props.presetMember');
    expect(kiosk).toContain('activationTimingStarted');
    expect(kiosk).not.toContain('Game time begins only after the first swipe');
  });
  it("房间卡只有四项，详情仅保留排队硬件与改名，不扩展店员数据权限", () => {
    expect(rooms).toContain('data-testid="admin-room-name"');
    expect(rooms).toContain('data-testid="admin-room-ip"');
    expect(rooms).toContain('data-testid="admin-room-status"');
    expect(rooms).toContain('data-testid="admin-room-game-time"');
    expect(rooms).toContain('data-testid="admin-room-queue-length"');
    expect(rooms).toContain('data-testid="admin-room-hardware"');
    expect(rooms).toContain('data-testid="admin-room-rename"');
    expect(rooms).toContain('if (!canViewRooms.value) return');
    for (const removed of ["临时状态实时预览", "临时数据", "room.players", "room.lastSequence", "room.lastEventType", "room-live-score-list", "实时积分与排名"]) expect(rooms).not.toContain(removed);
  });
  it("读卡仅在刷卡步骤接受数字及回车，不截获分钟输入", () => {
    expect(dialog).toContain('step.value !== "scan"');
    expect(dialog).toContain('event.key === "Enter"');
    expect(dialog).toContain('window.removeEventListener("keydown", onKeydown)');
    expect(dialog).not.toContain("window.confirm");
    expect(dialog).not.toContain("window.alert");
  });
  it("发卡重试复用原请求，还卡携带绑定身份避免影响新持有人", () => {
    expect(dialog).toContain("submitted ??=");
    expect(dialog).toContain("requestId: crypto.randomUUID()");
    expect(dialog).toContain("bindingId: band.value.bindingId");
    expect(dialog).toContain("currentRevision !== revision");
  });
  it("会员列表及自助查询独立展示账户余额，保留详情与历史", () => {
    expect(members).toContain('data-testid="admin-member-balance"');
    expect(members).toContain('data-testid="admin-member-wristband"');
    expect(members).toContain('data-testid="admin-member-edit"');
    expect(members).toContain('data-testid="admin-member-game-history-modal"');
    expect(kiosk).toContain("playerInfoState.info.account.remainingSeconds");
  });
});

describe("手环预充值与账户领取", () => {
  const precharges = read("./views/WristbandPrechargesView.vue");
  it("独立页面保持精简，不恢复旧手环管理多入口", () => {
    expect(app).toContain('{ id: "precharges", labelKey: "navPrecharges"');
    expect(app).toContain('<WristbandPrechargesView');
    for (const removed of ["window.confirm", "window.alert", "/wristbands/clear", "/wristbands/reclaim", "description="]) expect(precharges).not.toContain(removed);
    for (const selector of ["precharge-start", "precharge-search", "precharge-status", "precharge-refresh", "precharge-edit", "precharge-cancel"]) expect(precharges).toContain(`data-testid="${selector}"`);
  });
  it("先设置时长再连续刷卡，只在等待时捕获数字回车，重试复用请求号", () => {
    expect(precharges).toContain('if (!scanning.value || modal.value !== "prepare" || busy.value || submitted');
    expect(precharges).toContain('event.key === "Enter"');
    expect(precharges).toContain('if (!submitted)');
    expect(precharges).toContain('requestId: crypto.randomUUID()');
    expect(precharges).toContain('successCount.value++');
    expect(precharges).toContain('window.removeEventListener("keydown", onKeydown)');
    expect(precharges).toContain('version: selected.value.version');
  });
  it("柜台固定领取额度，带来源和版本，过期确认回到刷卡", () => {
    expect(dialog).toContain('found.status === "CHARGED") minutes.value = found.durationMinutes');
    expect(dialog).toContain('prechargeId: band.value.prechargeId, prechargeVersion: band.value.prechargeVersion');
    expect(dialog).toContain('|| isPrecharged');
    expect(dialog).toContain('cause.code === "PRECHARGE_CHANGED"');
    expect(dialog).toContain('step.value = "scan"');
  });
});
