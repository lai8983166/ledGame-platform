import { expect } from "@playwright/test";
import { test } from "../support/storeTest";
import {
  ACCEPTANCE_FACTORY_PASSWORD,
  ACCEPTANCE_FACTORY_USERNAME,
} from "../support/storeHarness";

test("出厂管理员创建店员后，店员可操作日常业务但不能查看运营数据或删除会员", async ({ store }) => {
  const page = store.adminPage;
  await page.getByTestId("admin-nav-settings").click();
  await page.getByRole("button", { name: /操作账号/ }).click();
  await page.getByTestId("operator-account-create").click();
  await page.getByTestId("operator-account-username").fill("counter-test");
  await page.getByTestId("operator-account-display-name").fill("验收前台");
  await page.getByTestId("operator-account-password").fill("counter-password");
  await page.getByTestId("operator-account-role").selectOption("CLERK");
  await page.getByTestId("operator-account-submit").click();
  await expect(page.getByText("counter-test", { exact: true })).toBeVisible();

  await page.getByTestId("operator-logout").click();
  await page.getByTestId("operator-login-username").fill("counter-test");
  await page.getByTestId("operator-login-password").fill("counter-password");
  await page.getByTestId("operator-login-submit").click();
  await expect(page.getByTestId("current-operator-name")).toHaveText("验收前台");
  await expect(page.getByTestId("admin-nav-settings")).toBeVisible();
  for (const tab of ["overview", "rooms", "records", "ranking"]) {
    await expect(page.getByTestId(`admin-nav-${tab}`)).toHaveCount(0);
  }
  await expect(page.getByTestId("admin-nav-members")).toBeVisible();
  await expect(page.getByTestId("admin-charge-start")).toBeVisible();
  await expect(page.getByTestId("admin-wristband-clear-card")).toBeVisible();

  await store.chargeWristband("2283055799", 30);
  await page.getByTestId("admin-nav-settings").click();
  await expect(page.getByTestId("settings-tab-features")).toBeVisible();
  await expect(page.getByTestId("settings-tab-accounts")).toHaveCount(0);
  await expect(page.getByTestId("settings-tab-backup")).toHaveCount(0);
  await page.getByTestId("settings-tab-features").click();
  await expect(page.getByTestId("child-mode-toggle")).toBeEnabled();
  await page.getByTestId("child-mode-toggle").click();
  await expect(page.getByTestId("child-mode-toggle")).toHaveAttribute("aria-checked", "true");
  await page.getByTestId("child-mode-toggle").click();
  await expect(page.getByTestId("child-mode-toggle")).toHaveAttribute("aria-checked", "false");

  const clerk = await store.loginOperatorForAssertions("counter-test", "counter-password");
  expect(clerk.accountType).toBe("CLERK");
  for (const apiPath of ["/api/dashboard/overview", "/api/operator-accounts", "/api/game-plays"]) {
    const response = await fetch(`${store.platformBaseUrl}${apiPath}`, {
      headers: { "X-Operator-Id": String(clerk.id) },
    });
    expect(response.status, apiPath).toBe(403);
  }

  await page.getByTestId("admin-nav-members").click();
  await page.getByTestId("admin-member-create").click();
  await page.getByTestId("admin-member-name-input").fill("店员创建的验收会员");
  await page.getByTestId("admin-member-phone-input").fill("13800000799");
  await page.getByTestId("admin-member-save").click();
  const memberRow = page.locator('tr[data-testid^="admin-member-"]').filter({ hasText: "13800000799" });
  await expect(memberRow).toBeVisible();
  await memberRow.getByRole("button").first().click();
  await expect(page.getByTestId("admin-member-delete")).toHaveCount(0);
  const memberId = (await memberRow.getAttribute("data-testid"))!.replace("admin-member-", "");
  const deletion = await fetch(`${store.platformBaseUrl}/api/members/${memberId}`, {
    method: "DELETE", headers: { "X-Operator-Id": String(clerk.id) },
  });
  expect(deletion.status).toBe(403);
  await page.getByRole("button", { name: "关闭详情", exact: true }).click();
  await page.getByTestId("admin-nav-wristbands").click();

  await page.getByTestId("operator-logout").click();
  await page.getByTestId("operator-login-username").fill(ACCEPTANCE_FACTORY_USERNAME);
  await page.getByTestId("operator-login-password").fill(ACCEPTANCE_FACTORY_PASSWORD);
  await page.getByTestId("operator-login-submit").click();
  await expect(page.getByTestId("admin-nav-settings")).toBeVisible();
});
