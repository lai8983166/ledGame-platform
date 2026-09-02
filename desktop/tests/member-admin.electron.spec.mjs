import { test, expect, _electron as electron } from "@playwright/test";
import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../..");
const factoryUsername = "desktop-admin";
const factoryPassword = "desktop-password";

async function login(page) {
  await expect(page.getByTestId("operator-login-page")).toBeVisible();
  await page.getByTestId("operator-login-username").fill(factoryUsername);
  await page.getByTestId("operator-login-password").fill(factoryPassword);
  await page.getByTestId("operator-login-submit").click();
  await expect(page.getByTestId("operator-authenticated-app")).toBeVisible();
}

async function freePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function waitForUrl(url, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { if ((await fetch(url)).ok) return; } catch { /* retry */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`renderer did not start: ${url}`);
}

async function waitForMemberWindow(desktop, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const window of desktop.windows()) {
      try {
        if (!window.isClosed() && await window.locator("#app").count()) return window;
      } catch { /* startup window can close while it is being inspected */ }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("member admin main window did not open after startup checks");
}

test("member admin owns an isolated backend, SQLite and restartable dynamic port", async () => {
  test.setTimeout(90000);
  const rendererPort = await freePort();
  const firstPort = await freePort();
  const secondPort = await freePort();
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-member-electron-"));
  const backupRoot = path.join(userData, "database-backup-test");
  const childEnv = { ...process.env };
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const desktopEnv = {
    ...childEnv,
    VITE_MEMBER_ADMIN_DEV_URL: `http://127.0.0.1:${rendererPort}`,
    LEDGAME_USER_DATA: userData,
    LEDGAME_PLATFORM_PORT: String(firstPort),
    LEDGAME_DATABASE_BACKUP_ENABLED: "true",
    LEDGAME_DATABASE_BACKUP_ROOT: backupRoot,
    LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST",
    PLATFORM_FACTORY_ADMIN_USERNAME: factoryUsername,
    PLATFORM_FACTORY_ADMIN_PASSWORD: factoryPassword,
    PLATFORM_FACTORY_ADMIN_DISPLAY_NAME: "桌面测试管理员",
  };
  const vite = spawn(process.execPath, [path.join(root, "apps/member-admin/node_modules/vite/bin/vite.js"), "--host", "127.0.0.1", "--port", String(rendererPort)], {
    cwd: path.join(root, "apps/member-admin"), env: childEnv, windowsHide: true, stdio: "ignore",
  });
  let desktop;
  try {
    await waitForUrl(`http://127.0.0.1:${rendererPort}`);
    desktop = await electron.launch({
      args: [path.join(root, "desktop/member-admin/main.cjs")],
      env: desktopEnv,
    });
    const page = await waitForMemberWindow(desktop);
    await expect(page.locator("#app")).toBeVisible();
    await expect.poll(async () => (await page.evaluate(() => window.memberAdminDesktop?.diagnostics())).state, { timeout: 45000 }).toBe("online");
    await login(page);
    await expect(page.getByTestId("admin-platform-connection")).toContainText(/已连接本机后端|connected/i, { timeout: 45000 });
    await page.getByTestId("operator-logout").click();
    await expect(page.getByTestId("operator-login-page")).toBeVisible();
    await login(page);
    await page.reload();
    await expect(page.getByTestId("operator-login-page")).toBeVisible();
    await login(page);
    let diagnostics = await page.evaluate(() => window.memberAdminDesktop.diagnostics());
    expect(diagnostics.port).toBe(firstPort);
    expect(diagnostics.dataPath).toContain(userData);
    await expect.poll(async () => fs.stat(diagnostics.dataPath).then(() => true).catch(() => false)).toBe(true);
    await expect(page.evaluate(() => window.memberAdminDesktop.request({ path: "/api/health", method: "GET", headers: {} }))).resolves.toMatchObject({ status: 200 });

    const duplicate = spawn(path.join(root, "node_modules/electron/dist/electron.exe"),
      [path.join(root, "desktop/member-admin/main.cjs")],
      { cwd: root, env: desktopEnv, windowsHide: true, stdio: "ignore" });
    const duplicateExit = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        duplicate.kill();
        reject(new Error("duplicate member admin instance did not exit"));
      }, 10000);
      duplicate.once("error", reject);
      duplicate.once("exit", (code) => { clearTimeout(timer); resolve(code); });
    });
    expect(duplicateExit).toBe(0);
    await expect(page.locator("#app")).toBeVisible();
    await expect.poll(async () => fetch(`http://127.0.0.1:${firstPort}/api/health`).then((response) => response.ok).catch(() => false)).toBe(true);

    diagnostics = await page.evaluate((port) => window.memberAdminDesktop.restartBackend(port), secondPort);
    expect(diagnostics.state, JSON.stringify(diagnostics)).toBe("online");
    expect(diagnostics.port).toBe(secondPort);
    await expect.poll(async () => fetch(`http://127.0.0.1:${secondPort}/api/health`).then((response) => response.ok).catch(() => false)).toBe(true);
    await expect.poll(async () => fetch(`http://127.0.0.1:${firstPort}/api/health`).then(() => true).catch(() => false)).toBe(false);

    await page.getByTestId("admin-nav-settings").click();
    await expect(page.getByTestId("member-desktop-runtime")).toContainText(String(secondPort));
    await page.getByTestId("settings-tab-backup").click();
    await expect(page.getByTestId("database-backup-management")).toBeVisible();
    await expect.poll(async () => fs.readFile(path.join(backupRoot, "latest/metadata.json"), "utf8")
      .then((text) => JSON.parse(text).environment).catch(() => null)).toBe("TEST");
    await page.close();
    await expect.poll(async () => fetch(`http://127.0.0.1:${secondPort}/api/health`).then(() => true).catch(() => false),
      { timeout: 30000 }).toBe(false);
  } finally {
    if (desktop) await desktop.close().catch(() => {});
    vite.kill();
    await fs.rm(userData, { recursive: true, force: true });
  }
  await expect.poll(async () => fetch(`http://127.0.0.1:${secondPort}/api/health`).then(() => true).catch(() => false)).toBe(false);
});
