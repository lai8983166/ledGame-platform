import { test, expect, _electron as electron } from "@playwright/test";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { prepareActivationLicense } from "./activation-fixture.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const exe = path.join(root, "release/member-admin/win-unpacked/LED Game 会员管理端.exe");

test("打包柜台：新会员发卡、失败后更正、取消、还卡保留余额、换卡及输入焦点", async () => {
  test.setTimeout(180_000);
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-counter-"));
  const port = await new Promise(resolve => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => { const port = server.address().port; server.close(() => resolve(port)); });
  });
  const env = {
    ...process.env, LEDGAME_USER_DATA: userData, LEDGAME_PLATFORM_PORT: String(port),
    LEDGAME_DATABASE_BACKUP_ENABLED: "false", LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST",
    PLATFORM_FACTORY_ADMIN_USERNAME: "counter-admin", PLATFORM_FACTORY_ADMIN_PASSWORD: "counter-password",
  };
  delete env.ELECTRON_RUN_AS_NODE;
  let desktop;
  let registration;
  let roomConnection;
  try {
    await prepareActivationLicense(userData);
    desktop = await electron.launch({ executablePath: exe, cwd: path.dirname(exe), env });
    let page;
    await expect.poll(async () => {
      for (const window of desktop.windows()) {
        try { if (!window.isClosed() && await window.getByTestId("operator-login-page").count()) { page = window; return true; } } catch { /* startup check can close */ }
      }
      return false;
    }, { timeout: 60_000 }).toBe(true);
    await page.getByTestId("operator-login-username").fill("counter-admin");
    await page.getByTestId("operator-login-password").fill("counter-password");
    await page.getByTestId("operator-login-submit").click();
    await expect(page.getByTestId("admin-card-issue")).toBeVisible();
    await expect(page.getByTestId("admin-page-rooms")).toBeVisible();
    await expect(page.getByRole("heading", { name: "运营管理", exact: true })).toBeVisible();
    await expect(page.getByTestId("admin-nav-wristbands")).toHaveCount(0);
    const api = async (path, method = "GET", body) => {
      const response = await fetch(`http://127.0.0.1:${port}${path}`, {
        method, headers: { "Content-Type": "application/json" }, ...(body ? { body: JSON.stringify(body) } : {}),
      });
      expect(response.ok).toBe(true);
      return response.json();
    };
    const scan = async uid => { await page.keyboard.type(uid); await page.keyboard.press("Enter"); };
    const selectMember = async () => {
      await page.getByTestId("admin-nav-rooms").click();
      await page.getByTestId("admin-card-issue").click();
      await page.getByTestId("card-member-phone").fill("13800138888");
      await page.getByTestId("card-member-next").click();
      await expect(page.getByTestId("card-scan-prompt")).toBeVisible();
    };

    await test.step("模拟真实房间消息验证精简卡片、排队硬件详情与改名", async () => {
      roomConnection = new WebSocket(`ws://127.0.0.1:${port}/ws/rooms`);
      await new Promise((resolve, reject) => {
        roomConnection.addEventListener("open", resolve, { once: true });
        roomConnection.addEventListener("error", reject, { once: true });
      });
      roomConnection.send(JSON.stringify({ type: "HELLO" }));
      roomConnection.send(JSON.stringify({ type: "ROOM_SNAPSHOT", eventId: "operations-ui-1", sequence: 1, state: {
        engineState: "RUNNING", gameName: "不应展示的游戏名",
        gameTime: { mode: "LIMITED", remainingMillis: 120000, running: true },
        queueSummary: { waiting: [{}, {}] },
        hardware: [{ id: "controller", name: "验收控制器", status: "online", detail: "控制器已连接" }],
      } }));
      const card = page.getByTestId("admin-room-127.0.0.1");
      await expect(card).toBeVisible();
      await expect(card.getByTestId("admin-room-status")).toHaveText("游戏中");
      await expect(card.getByTestId("admin-room-game-time")).toHaveText(/^0[12]:[0-5]\d$/);
      await expect(card).not.toContainText("不应展示的游戏名");
      await expect(card.getByTestId("admin-room-queue-length")).toHaveCount(0);
      await expect(card.locator("button")).toHaveCount(0);
      await card.click();
      await expect(page.getByTestId("admin-room-queue-length")).toHaveText("2");
      await expect(page.getByTestId("admin-room-hardware")).toContainText("验收控制器");
      await expect(page.getByRole("dialog")).not.toContainText("实时积分");
      await page.getByTestId("admin-room-rename").click();
      const edit = page.getByRole("dialog", { name: "修改房间名称", exact: true });
      await edit.getByRole("textbox").fill("验收房间");
      await edit.getByRole("button", { name: "保存名称", exact: true }).click();
      await expect(edit).toHaveCount(0);
      await expect(card.getByTestId("admin-room-name")).toHaveText("验收房间");
      roomConnection.send(JSON.stringify({ type: "ROOM_SNAPSHOT", eventId: "operations-ui-2", sequence: 2, state: {
        engineState: "IDLE", queueSummary: { waiting: [] },
        hardware: [{ id: "controller", name: "验收控制器", status: "warning", detail: "测试硬件异常" }],
      } }));
      await expect(page.getByTestId("admin-room-queue-length")).toHaveText("0");
      await expect(page.getByTestId("admin-room-hardware")).toContainText("测试硬件异常");
      await page.screenshot({ path: test.info().outputPath("运营管理详情.png") });
      await page.getByRole("button", { name: "关闭详情", exact: true }).click();
      roomConnection.close();
      await expect(card.getByTestId("admin-room-status")).toHaveText("离线");
      await expect(card.getByTestId("admin-room-game-time")).toHaveText("--");
      await page.getByTestId("admin-nav-overview").click();
      await expect(page.locator(".room-summary")).toHaveCount(0);
      await page.getByTestId("admin-nav-members").click();
      await expect(page.getByTestId("admin-card-issue")).toHaveCount(0);
      await expect(page.getByTestId("admin-card-return")).toHaveCount(0);
      await page.getByTestId("admin-nav-rooms").click();
      await page.screenshot({ path: test.info().outputPath("运营管理首页.png") });
    });

    await test.step("新会员选择、数字回车读卡与不足余额后修正充值分钟", async () => {
      await page.getByTestId("admin-card-issue").click();
      await page.getByTestId("card-member-phone").fill("13800138888");
      await page.getByTestId("card-member-name").fill("柜台测试会员");
      await page.getByTestId("card-member-next").click();
      await expect(page.getByTestId("card-scan-prompt")).toBeVisible();
      await scan("2283055618");
      await expect(page.getByTestId("card-confirmation")).toBeVisible();
      await page.getByTestId("card-submit").click();
      await expect(page.getByTestId("card-error")).toContainText("余额不足");
      await expect(page.getByTestId("card-minutes")).toBeEnabled();
      await page.getByTestId("card-minutes").fill("30");
      await page.screenshot({ path: test.info().outputPath("发卡确认.png") });
      await page.getByTestId("card-submit").click();
      await expect(page.getByTestId("card-confirmation")).toHaveCount(0);
      await page.getByTestId("admin-nav-members").click();
      await expect(page.getByTestId("admin-member-balance")).toContainText("30 分");
      expect(await api("/api/wristbands/2283055618")).toMatchObject({ status: "READY", remainingSeconds: 1800 });
    });

    await test.step("取消不还卡；会员详情、编辑、记录窗口关闭后仍能输入", async () => {
      await page.getByTestId("admin-nav-rooms").click();
      await page.getByTestId("admin-card-return").click();
      await scan("2283055618");
      await expect(page.getByTestId("card-confirmation")).toBeVisible();
      await page.getByTestId("card-cancel").click();
      expect(await api("/api/wristbands/2283055618")).toMatchObject({ status: "READY", remainingSeconds: 1800 });
      await page.getByTestId("admin-nav-members").click();
      await page.getByTestId("admin-member-name").click();
      await page.getByTestId("admin-member-edit").click();
      await page.getByTestId("admin-member-edit-name").fill("编辑后测试会员");
      await page.getByTestId("admin-member-edit-save").click();
      await expect(page.getByTestId("admin-member-edit-name")).toHaveCount(0);
      await page.getByTestId("admin-member-charge-history-button").click();
      await expect(page.getByTestId("admin-member-charge-history-modal")).toContainText("30");
      await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
      await page.getByRole("button", { name: "关闭详情", exact: true }).click();
      await selectMember();
      await scan("2283055618");
      await expect(page.getByTestId("card-error")).toContainText("已发卡");
      await page.getByTestId("card-cancel").click();
    });

    await test.step("游戏激活后还卡暂停；再次取消发卡不消耗，换卡保留余额", async () => {
      await api("/api/game-access/activate", "POST", { uid: "2283055618", deviceId: "counter-test" });
      await page.getByTestId("admin-card-return").click();
      await scan("2283055618");
      await expect(page.getByTestId("card-confirmation")).toBeVisible();
      await page.getByTestId("card-submit").click();
      await expect(page.getByTestId("card-confirmation")).toHaveCount(0);
      const paused = (await api("/api/player-info?phone=13800138888")).account;
      expect(paused.consuming).toBe(false);
      expect(paused.remainingSeconds).toBeGreaterThan(1700);
      const kioskExe = path.join(root, "release/registration-kiosk/win-unpacked/LED Game 自助注册端.exe");
      registration = await electron.launch({
        executablePath: kioskExe, cwd: path.dirname(kioskExe),
        env: { ...env, LEDGAME_USER_DATA: path.join(userData, "registration") },
      });
      const operator = await registration.firstWindow();
      await operator.getByTestId("operator-host").fill("127.0.0.1");
      await operator.getByTestId("operator-port").fill(String(port));
      await expect(operator.getByTestId("operator-launch")).toBeEnabled();
      await operator.getByTestId("operator-launch").click();
      await expect.poll(() => registration.windows().length).toBe(2);
      const kiosk = registration.windows().find(window => window !== operator);
      await kiosk.getByTestId("kiosk-player-info").click();
      await kiosk.getByTestId("kiosk-info-phone").fill("13800138888");
      await kiosk.getByTestId("kiosk-info-submit").click();
      await expect(kiosk.getByTestId("kiosk-info-result")).toBeVisible();
      await expect(kiosk.getByTestId("kiosk-info-account-balance")).toBeVisible();
      await expect(kiosk.locator('article[data-testid^="kiosk-info-wristband-"]')).toHaveCount(0);
      await kiosk.screenshot({ path: test.info().outputPath("还卡后自助查询.png") });
      await kiosk.evaluate(() => { void window.registrationDesktop.staffExit("888888"); }).catch(cause => {
        if (!String(cause).includes("Target page, context or browser has been closed")) throw cause;
      });
      await expect.poll(() => registration.windows().length).toBe(1);
      await registration.close();
      registration = null;
      await selectMember();
      await scan("2283055619");
      await expect(page.getByTestId("card-confirmation")).toBeVisible();
      await page.getByTestId("card-minutes").fill("60");
      await page.getByTestId("card-cancel").click();
      expect((await api("/api/player-info?phone=13800138888")).account).toEqual(paused);
      expect((await fetch(`http://127.0.0.1:${port}/api/wristbands/2283055619`)).status).toBe(404);
      await selectMember();
      await scan("2283055619");
      await expect(page.getByTestId("card-confirmation")).toBeVisible();
      await expect(page.getByTestId("card-minutes")).toHaveValue("0");
      await page.getByTestId("card-submit").click();
      await expect(page.getByTestId("card-confirmation")).toHaveCount(0);
      expect(await api("/api/wristbands/2283055619")).toMatchObject({ status: "READY", remainingSeconds: paused.remainingSeconds });
      await page.getByTestId("admin-nav-members").click();
      await page.screenshot({ path: test.info().outputPath("会员列表.png") });
    });
    await page.close();
    await desktop.close();
    desktop = null;
    await expect.poll(async () => fetch(`http://127.0.0.1:${port}/api/health`).then(() => true).catch(() => false)).toBe(false);
  } finally {
    if (roomConnection) roomConnection.close();
    if (registration) {
      // The customer window intentionally blocks ordinary close; do not hang test cleanup on that guard.
      await registration.evaluate(({ BrowserWindow }) => {
        for (const window of BrowserWindow.getAllWindows()) window.destroy();
      }).catch(() => {});
      await registration.close().catch(() => {});
    }
    if (desktop) await desktop.close().catch(() => {});
    await fs.rm(userData, { recursive: true, force: true });
  }
});
