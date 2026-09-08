import { expect, test } from "@playwright/test";
import { StoreAcceptanceHarness } from "../support/storeHarness";

test.describe.configure({ mode: "serial" });

test("多人核心流程：三种 Simple 玩法均需刷满人数，并为每位会员记录同一局结果", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  const players = [
    { phone: "13800000041", name: "多人验收会员甲", uid: "2283055641" },
    { phone: "13800000042", name: "多人验收会员乙", uid: "2283055642" },
  ];
  try {
    await test.step("准备两名测试会员及两只真实格式手环", async () => {
      for (const player of players) {
        await store.chargeWristband(player.uid, 60);
        await store.registerAndBind(player);
      }
    });

    for (const [index, variantName] of ["simple", "normal", "diffcult"].entries()) {
      await test.step(`${variantName}：把本次验收的逐关奖励配置为 1`, async () => {
        await store.setSimpleLevelRewardPoints(variantName, 1);
      });
      await test.step(`${variantName}：选择 2 人并依次刷卡，人数不足时不得开始`, async () => {
        await store.startMultiplayerGame(players.map((player) => player.uid), variantName);
        await expect.poll(() => store.currentEngineState()).toBe("RUNNING");
      });

      await test.step(`${variantName}：通过共享游戏输入自然完成`, async () => {
        await store.completeCurrentGameNaturally();
        await expect.poll(() => store.currentEngineState()).toBe("STOPPED");
      });

      await test.step(`${variantName}：两位会员分别获得同一 session 的同分结算`, async () => {
        await store.assertMultiplayerNaturalState(
          players.map((player) => player.phone),
          players.map((player) => player.uid),
          variantName,
          index + 1,
        );
      });
    }
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});

test("多人 Rank 并列第一：逐玩家上报原始分与逐关奖励，重复结算保持幂等", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  const players = [
    { phone: "13800000051", name: "Rank 验收会员甲", uid: "2283055651" },
    { phone: "13800000052", name: "Rank 验收会员乙", uid: "2283055652" },
  ];
  try {
    for (const player of players) {
      await store.chargeWristband(player.uid, 60);
      await store.registerAndBind(player);
    }
    await store.setRankLevelRewardPoints("rank-type1", 7);
    await store.startMultiplayerGame(players.map((player) => player.uid), "rank-type1");
    await expect.poll(() => store.currentEngineState()).toBe("RUNNING");
    await store.completeCurrentStageSuccessfully();
    await expect.poll(() => store.currentEngineState()).toBe("STOPPED");
    await store.assertRankTieNaturalState(
      players.map((player) => player.phone),
      players.map((player) => player.uid),
      "rank-type1",
      7,
    );
    for (const player of players) {
      await store.assertConflictingDuplicateSettlementIsIgnored(player.phone);
    }
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
