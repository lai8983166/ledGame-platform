import { test } from "@playwright/test";
import { StoreAcceptanceHarness } from "../support/storeHarness";

test("Windows 桌面双端复用门店核心流程并安全返回店员启动页", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo, { platformClients: "desktop" });
  try {
    await test.step("桌面会员管理端通过自有 SQLite 为手环充时", async () => {
      await store.chargeWristband("2283055698", 60);
      await store.chargeWristband("2283055699", 60);
    });
    await test.step("桌面自助注册端在独立顾客窗口注册并绑定", async () => {
      await store.registerAndBind({ phone: "13800000981", name: "桌面验收会员一", uid: "2283055698" });
      await store.registerAndBind({ phone: "13800000982", name: "桌面验收会员二", uid: "2283055699" });
    });
    await test.step("游戏、排队、自然结算与跨端数据核对复用现有核心流程", async () => {
      await store.startGame("2283055698");
      await store.enqueueNextGame("2283055699");
      await store.completeCurrentGameNaturally();
      await store.assertNaturalCrossClientState("13800000981", "2283055698");
    });
    await test.step("店员受保护退出恢复运维启动页", async () => {
      await store.exitRegistrationKioskToOperator();
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
