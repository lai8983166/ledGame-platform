import { expect, test } from "@playwright/test";
import { StoreAcceptanceHarness } from "../support/storeHarness";

test.describe.configure({ mode: "serial" });

test("PRODUCTION 软件形态：真实 TCP 地砖输入自然完成游戏并结算", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo, { runtimeMode: "PRODUCTION" });
  try {
    await test.step("准备已充值并绑定手环的会员", async () => {
      await store.chargeWristband("2283055640", 60);
      await store.registerAndBind({ phone: "13800000040", name: "验收生产形态会员", uid: "2283055640" });
    });

    await test.step("以 PRODUCTION 模式启动游戏", async () => {
      await store.startGame("2283055640");
      await expect.poll(() => store.currentRuntimeMode()).toBe("PRODUCTION");
    });

    await test.step("经真实 TCP 地砖协议发送 DOWN/UP 并自然完成", async () => {
      await store.completeCurrentGameThroughFloor();
      await expect.poll(() => store.currentEngineState(), { timeout: 30_000 }).toBe("STOPPED");
    });

    await test.step("核对自然结算、积分、记录和手环余额", async () => {
      await store.assertNaturalCrossClientState("13800000040", "2283055640");
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
