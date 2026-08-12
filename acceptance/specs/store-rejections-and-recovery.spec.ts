import { expect, test } from "@playwright/test";
import { StoreAcceptanceHarness } from "../support/storeHarness";

test.describe.configure({ mode: "serial" });

test("异常操作：拒绝重复绑定，余额耗尽的手环不能开始游戏", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  try {
    await store.chargeWristband("2283055620", 1);
    await store.registerAndBind({ phone: "13800000020", name: "验收会员二十", uid: "2283055620" });

    await test.step("第二位会员不能抢占已经存在的手环绑定", async () => {
      await store.expectDuplicateBindingRejected(
        { phone: "13800000021", name: "验收会员二十一", uid: "2283055620" },
        "2283055620",
      );
    });

    await test.step("推进隔离测试时钟后，游戏端明确拒绝余额耗尽的手环", async () => {
      await store.activateWristbandThroughPublicApi("2283055620");
      await store.restartPlatform(120);
      await store.expectGameAdmissionRejected("2283055620", "WRISTBAND_EXPIRED");
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});

test("排队幂等：同一只手环重复提交仍然只有一个等待项", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  try {
    await test.step("准备两位已充值并绑定手环的会员", async () => {
      await store.chargeWristband("2283055621", 60);
      await store.chargeWristband("2283055622", 60);
      await store.registerAndBind({ phone: "13800000022", name: "验收会员二十二", uid: "2283055621" });
      await store.registerAndBind({ phone: "13800000023", name: "验收会员二十三", uid: "2283055622" });
    });
    await test.step("开始当前游戏并将第二位会员加入队列", async () => {
      await store.startGame("2283055621");
      await store.enqueueNextGame("2283055622");
    });
    await test.step("重复提交同一手环后仍然只有一个等待项", async () => {
      await store.enqueueSameGameAgain("2283055622");
      await expect.poll(() => store.waitingQueueLength()).toBe(1);
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});

test("重启恢复：会员管理后端重启后房间自动重连并保留游戏队列", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  try {
    await test.step("准备进行中的游戏和一个排队玩家", async () => {
      await store.chargeWristband("2283055623", 60);
      await store.chargeWristband("2283055624", 60);
      await store.registerAndBind({ phone: "13800000024", name: "验收会员二十四", uid: "2283055623" });
      await store.registerAndBind({ phone: "13800000025", name: "验收会员二十五", uid: "2283055624" });
      await store.startGame("2283055623");
      await store.enqueueNextGame("2283055624");
    });
    await test.step("重启会员管理后端并验证房间和队列恢复", async () => {
      await store.restartPlatform();
      await store.assertRoomReconnected(1);
    });
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
