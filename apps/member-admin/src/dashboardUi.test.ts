import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync(new URL("./views/DashboardView.vue", import.meta.url), "utf8");
const appSource = readFileSync(new URL("./App.vue", import.meta.url), "utf8");
const dataSource = readFileSync(new URL("./data.ts", import.meta.url), "utf8");

describe("real member admin dashboard", () => {
  it("loads real overview metrics and rooms with refresh, error and empty states", () => {
    expect(source).toContain("platformApi.getDashboardOverview");
    expect(source).toContain("platformApi.listRooms");
    expect(source).toContain('data-testid="admin-dashboard-refresh"');
    expect(source).toContain('data-testid="admin-dashboard-error"');
    expect(source).toContain('data-testid="admin-dashboard-empty-rooms"');
    expect(source).toContain("mapRoomStatus");
    expect(source).not.toContain("createRooms");
  });

  it("removes simulated health and money copy while exposing stable real metrics", () => {
    for (const selector of ["total-members", "new-members-today", "wristbands-charged-today", "revenue-today"]) {
      expect(source).toContain(`admin-dashboard-${selector}`);
    }
    expect(source).not.toContain("health-card");
    expect(source).not.toContain("设备健康");
    expect(source).not.toContain("6,840");
    expect(source).toContain("revenueTodayCents");
    expect(source).not.toContain("dashboard" + "Title");
    expect(source).not.toContain("dashboard" + "RealData");
    expect(source).toContain("dashboard-refresh-button");
  });

  it("does not retain unused room/member samples or fixed shell indicators", () => {
    expect(dataSource).not.toContain("createRooms");
    expect(dataSource).not.toContain("createMembers");
    expect(dataSource).not.toContain("星际穿梭");
    expect(dataSource).not.toContain("陈小宇");
    expect(appSource).not.toContain("nav-item__count");
    expect(appSource).not.toContain("roomsConnected");
    expect(appSource).not.toContain("demoData");
    expect(appSource).not.toContain("08月02日");
    expect(appSource).not.toContain("notification-button");
  });
});
