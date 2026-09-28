import { expect } from "@playwright/test";
import { test } from "../support/storeTest";

test("主动结束仍是中止记录：零积分且不作为正常结算", async ({ store }) => {
  await store.chargeWristband("2283055630", 60);
  await store.registerAndBind({ phone: "13800000030", name: "验收中止会员", uid: "2283055630" });
  await store.startGame("2283055630");
  await store.endCurrentGame();
  await store.assertManualAbortState("13800000030");
});

test("平台短暂离线：本地保留一条待投递结算，恢复后自动送达", async ({ store }) => {
  await store.chargeWristband("2283055631", 60);
  await store.registerAndBind({ phone: "13800000031", name: "验收离线会员", uid: "2283055631" });
  await store.startGame("2283055631");

  await store.stopPlatform();
  await store.completeCurrentGameNaturally();
  await expect.poll(async () => (await store.settlementDiagnostics()).counts.pending).toBe(1);
  await store.startPlatform();

  await expect.poll(async () => (await store.settlementDiagnostics()).counts.delivered, { timeout: 30_000 }).toBe(1);
  await expect.poll(async () => (await store.settlementDiagnostics()).counts.pending).toBe(0);
  await store.assertNaturalCrossClientState("13800000031", "2283055631");
});

test("游戏后端重启：从本地数据库恢复未投递结算，无需重建已结束游戏", async ({ store }) => {
  await store.chargeWristband("2283055632", 60);
  await store.registerAndBind({ phone: "13800000032", name: "验收恢复会员", uid: "2283055632" });
  await store.startGame("2283055632");

  await store.stopPlatform();
  await store.completeCurrentGameNaturally();
  await expect.poll(async () => (await store.settlementDiagnostics()).counts.pending).toBe(1);
  await store.restartGameBackend();
  await expect.poll(async () => (await store.settlementDiagnostics()).counts.pending).toBe(1);
  await store.startPlatform();

  await expect.poll(async () => (await store.settlementDiagnostics()).counts.delivered, { timeout: 30_000 }).toBe(1);
  await store.assertNaturalCrossClientState("13800000032", "2283055632");
});
