import { test as base } from "@playwright/test";
import { StoreAcceptanceHarness, type StoreAcceptanceOptions } from "./storeHarness";

// Playwright fixture 的清理时间独立于业务用例，避免用例超时后遗留 Java 服务。
export const test = base.extend<{ storeOptions: StoreAcceptanceOptions; store: StoreAcceptanceHarness }>({
  storeOptions: [{}, { option: true }],
  store: [async ({ storeOptions }, use, testInfo) => {
    const store = await StoreAcceptanceHarness.start(testInfo, storeOptions);
    try {
      await use(store);
    } finally {
      await store.stop(testInfo.status === testInfo.expectedStatus);
    }
  }, { timeout: 240_000 }],
});
