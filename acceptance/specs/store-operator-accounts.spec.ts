import { expect, test } from "@playwright/test";
import {
  ACCEPTANCE_FACTORY_PASSWORD,
  ACCEPTANCE_FACTORY_USERNAME,
  StoreAcceptanceHarness,
} from "../support/storeHarness";

test("出厂管理员创建普通操作员后，普通操作员仅使用日常功能", async ({}, testInfo) => {
  const store = await StoreAcceptanceHarness.start(testInfo);
  try {
    const page = store.adminPage;
    await page.getByTestId("admin-nav-settings").click();
    await page.getByRole("button", { name: /操作账号/ }).click();
    await page.getByTestId("operator-account-create").click();
    await page.getByTestId("operator-account-username").fill("counter-test");
    await page.getByTestId("operator-account-display-name").fill("验收前台");
    await page.getByTestId("operator-account-password").fill("counter-password");
    await page.getByTestId("operator-account-submit").click();
    await expect(page.getByText("counter-test", { exact: true })).toBeVisible();

    await page.getByTestId("operator-logout").click();
    await page.getByTestId("operator-login-username").fill("counter-test");
    await page.getByTestId("operator-login-password").fill("counter-password");
    await page.getByTestId("operator-login-submit").click();
    await expect(page.getByTestId("current-operator-name")).toHaveText("验收前台");
    await expect(page.getByTestId("admin-nav-settings")).toHaveCount(0);
    await expect(page.getByTestId("admin-nav-members")).toBeVisible();
    await expect(page.getByTestId("admin-charge-start")).toBeVisible();
    await expect(page.getByTestId("admin-wristband-clear-card")).toHaveCount(0);

    await store.chargeWristband("2283055799", 30);

    await page.getByTestId("operator-logout").click();
    await page.getByTestId("operator-login-username").fill(ACCEPTANCE_FACTORY_USERNAME);
    await page.getByTestId("operator-login-password").fill(ACCEPTANCE_FACTORY_PASSWORD);
    await page.getByTestId("operator-login-submit").click();
    await expect(page.getByTestId("admin-nav-settings")).toBeVisible();
  } finally {
    await store.stop(testInfo.status === testInfo.expectedStatus);
  }
});
