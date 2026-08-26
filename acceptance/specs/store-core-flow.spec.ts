import { expect, test } from "@playwright/test";
import { StoreAcceptanceHarness } from "../support/storeHarness";

test.describe.configure({ mode: "serial" });

test("门店黄金流程：充值、注册绑定、开始游戏、排队、自动切换并核对数据", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  try {
    await test.step("充值弹层在刷卡前和已刷卡后取消都不会写数据库", async () => {
      await store.assertChargeCancellationHasNoWrite("2283055600");
    });
    await test.step("会员管理端为当前玩家和排队玩家的手环充值", async () => {
      await store.chargeWristband("2283055618", 60);
      await store.chargeWristband("2283055619", 60);
    });

    await test.step("自助注册端创建会员并绑定刷到的手环", async () => {
      await store.registerAndBind({ phone: "13800000001", name: "验收会员一", uid: "2283055618" });
      await store.registerAndBind({ phone: "13800000002", name: "验收会员二", uid: "2283055619" });
    });
    await test.step("运营总览显示真实会员、充值收入与已连接房间", async () => {
      await store.assertDashboardOverview({ totalMembers: 2, newMembersToday: 2, chargesToday: 2, revenueTodayCents: 12_000 });
    });

    await test.step("Electron 游戏端验证当前手环并启动模拟游戏", async () => {
      await store.startGame("2283055618");
      await expect.poll(() => store.currentEngineState()).toBe("RUNNING");
    });

    await test.step("游戏进行期间将第二只手环加入队列", async () => {
      await store.enqueueNextGame("2283055619");
      await expect.poll(() => store.waitingQueueLength()).toBe(1);
    });

    await test.step("通过调试面板命中确定目标，自然完成当前游戏并自动切换到排队玩家", async () => {
      await store.completeCurrentGameNaturally();
      await expect.poll(() => store.currentWristbandUid()).toBe("2283055619");
    });

    await test.step("核对会员管理端、玩家信息查询与公开接口的数据一致性", async () => {
      await store.assertNaturalCrossClientState("13800000001", "2283055618");
      await store.assertConflictingDuplicateSettlementIsIgnored("13800000001");
    });
    await test.step("管理端与自助端均可在删除后用同手机号创建全新零积分会员", async () => {
      await store.assertMemberDeletionReregistrationFlows();
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
