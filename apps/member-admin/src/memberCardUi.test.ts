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
    expect(members).not.toContain('data-testid="admin-member-create"');
    expect(members).not.toContain("会员与手环分离");
    expect(dialog).not.toContain("description=");
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
