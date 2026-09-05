import { test, expect, _electron as electron } from "@playwright/test";
import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../..");
const executablePath = process.env.LEDGAME_MEMBER_ADMIN_PACKAGED_EXE
  || path.join(root, "release/member-admin/win-unpacked/LED Game 会员管理端.exe");
const factoryUsername = "packaged-admin";
const factoryPassword = "packaged-password";

test.describe.configure({ mode: "serial" });

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

function environment(userData, backupRoot, port) {
  const env = {
    ...process.env,
    LEDGAME_USER_DATA: userData,
    LEDGAME_PLATFORM_PORT: String(port),
    LEDGAME_DATABASE_BACKUP_ENABLED: "true",
    LEDGAME_DATABASE_BACKUP_ROOT: backupRoot,
    LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST",
    PLATFORM_FACTORY_ADMIN_USERNAME: factoryUsername,
    PLATFORM_FACTORY_ADMIN_PASSWORD: factoryPassword,
    PLATFORM_FACTORY_ADMIN_DISPLAY_NAME: "打包验收管理员",
  };
  delete env.ELECTRON_RUN_AS_NODE;
  return env;
}

async function launchPackaged(env) {
  await expect.poll(async () => fs.stat(executablePath).then(() => true).catch(() => false), {
    message: `member admin package is missing: ${executablePath}`,
  }).toBe(true);
  return electron.launch({ executablePath, cwd: path.dirname(executablePath), env });
}

async function waitForStartupWindow(desktop, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const window of desktop.windows()) {
      try {
        if (!window.isClosed() && await window.getByText("正在检查本机数据").count()) return window;
      } catch { /* window can be destroyed after a fast startup check */ }
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("packaged startup check window was not observed");
}

async function waitForMemberWindow(desktop, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const window of desktop.windows()) {
      try {
        if (!window.isClosed() && await window.getByTestId("operator-login-page").count()) return window;
      } catch { /* startup window can close while inspected */ }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("packaged member admin main window did not open");
}

async function loginUi(page, username = factoryUsername, password = factoryPassword) {
  await expect(page.getByTestId("operator-login-page")).toBeVisible();
  await page.getByTestId("operator-login-username").fill(username);
  await page.getByTestId("operator-login-password").fill(password);
  await page.getByTestId("operator-login-submit").click();
  await expect(page.getByTestId("operator-authenticated-app")).toBeVisible();
}

async function request(page, input) {
  const response = await page.evaluate((value) => window.memberAdminDesktop.request(value), input);
  const body = response.body ? JSON.parse(response.body) : null;
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${body?.code || response.status}: ${body?.message || response.body}`);
  }
  return body;
}

async function loginApi(page, username = factoryUsername, password = factoryPassword) {
  return request(page, {
    path: "/api/operator-auth/login",
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

async function backupStatus(page, operatorId) {
  return request(page, {
    path: "/api/database-backup/status",
    method: "GET",
    headers: { "X-Operator-Id": operatorId },
  });
}

async function createMember(page, operatorId, phone, name) {
  return request(page, {
    path: "/api/members",
    method: "POST",
    headers: { "X-Operator-Id": operatorId },
    body: JSON.stringify({ phone, name, createdBy: "packaged-acceptance" }),
  });
}

async function waitForPort(port, online, timeoutMs = 30_000) {
  await expect.poll(async () => fetch(`http://127.0.0.1:${port}/api/health`)
    .then((response) => response.ok).catch(() => false), { timeout: timeoutMs }).toBe(online);
}

async function killTree(pid) {
  await new Promise((resolve, reject) => {
    const child = spawn("taskkill", ["/pid", String(pid), "/t", "/f"], {
      windowsHide: true,
      stdio: "ignore",
    });
    child.once("error", reject);
    child.once("exit", () => resolve());
  });
}

async function closeNormally(desktop, page, port) {
  await page.close();
  await waitForPort(port, false);
  await desktop.close().catch(() => {});
}

test("final package shows the startup check, creates a TEST backup, enforces IPC authorization and is single-instance", async () => {
  test.setTimeout(180_000);
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-packaged-first-start-"));
  const backupRoot = path.join(userData, "isolated-backup");
  const port = await freePort();
  const env = environment(userData, backupRoot, port);
  let desktop;
  try {
    desktop = await launchPackaged(env);
    const startup = await waitForStartupWindow(desktop);
    await expect(startup.getByText("正在检查本机数据")).toBeVisible();
    const startupText = await startup.locator("body").innerText();
    expect(startupText).not.toContain("导入");
    expect(startupText).not.toContain("candidate");

    const page = await waitForMemberWindow(desktop);
    await loginUi(page);
    const factory = await loginApi(page);
    await page.getByTestId("admin-nav-settings").click();
    await page.getByTestId("settings-tab-backup").click();
    const management = page.getByTestId("database-backup-management");
    await expect(management).toBeVisible();
    await expect(management).not.toContainText("未找到可用异盘");
    await expect(management).not.toContainText("最后成功备份暂无");
    await expect.poll(async () => fs.readFile(path.join(backupRoot, "latest/metadata.json"), "utf8")
      .then((value) => JSON.parse(value).environment).catch(() => null)).toBe("TEST");

    const created = await request(page, {
      path: "/api/operator-accounts",
      method: "POST",
      headers: { "X-Operator-Id": factory.id },
      body: JSON.stringify({ username: "packaged-operator", displayName: "打包测试操作员", password: "operator-password" }),
    });
    await expect(page.evaluate((operatorId) => window.memberAdminDesktop.importBackupDatabase("missing", operatorId), created.id))
      .rejects.toThrow(/IMPORT_FORBIDDEN|只有出厂账号/);
    await page.getByTestId("operator-logout").click();
    await loginUi(page, "packaged-operator", "operator-password");
    await expect(page.getByTestId("admin-nav-settings")).toHaveCount(0);

    const duplicate = spawn(executablePath, [], {
      cwd: path.dirname(executablePath), env, windowsHide: true, stdio: "ignore",
    });
    const duplicateExit = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        duplicate.kill();
        reject(new Error("duplicate packaged member admin instance did not exit"));
      }, 15_000);
      duplicate.once("error", reject);
      duplicate.once("exit", (code) => { clearTimeout(timer); resolve(code); });
    });
    expect(duplicateExit).toBe(0);
    await waitForPort(port, true);

    await closeNormally(desktop, page, port);
    desktop = undefined;
    const reopened = await launchPackaged(env);
    const reopenedPage = await waitForMemberWindow(reopened);
    await waitForPort(port, true);
    await closeNormally(reopened, reopenedPage, port);
  } finally {
    if (desktop) {
      await killTree(desktop.process().pid).catch(() => {});
      await desktop.close().catch(() => {});
    }
    await fs.rm(userData, { recursive: true, force: true });
  }
});

test("final package catches up a committed database after Electron and Java are force-killed", async () => {
  test.setTimeout(180_000);
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-packaged-kill-"));
  const backupRoot = path.join(userData, "isolated-backup");
  const detachedRoot = `${backupRoot}-detached`;
  const port = await freePort();
  const env = environment(userData, backupRoot, port);
  let desktop;
  try {
    desktop = await launchPackaged(env);
    const page = await waitForMemberWindow(desktop);
    const factory = await loginApi(page);
    const initialStatus = await backupStatus(page, factory.id);
    expect(initialStatus.state).toBe("READY_PROTECTED");

    await fs.rename(backupRoot, detachedRoot);
    await fs.writeFile(backupRoot, "simulated disconnected target", "utf8");
    await createMember(page, factory.id, "13990000001", "强制终止恢复会员");
    await expect.poll(async () => backupStatus(page, factory.id).then((value) => value.state), {
      timeout: 15_000,
    }).toBe("READY_DEGRADED");

    const killedPid = desktop.process().pid;
    await killTree(killedPid);
    await waitForPort(port, false);
    desktop = undefined;
    await fs.rm(backupRoot, { force: true });
    await fs.rename(detachedRoot, backupRoot);

    const restarted = await launchPackaged(env);
    const restartedPage = await waitForMemberWindow(restarted);
    const restartedFactory = await loginApi(restartedPage);
    await expect.poll(async () => backupStatus(restartedPage, restartedFactory.id), {
      timeout: 30_000,
    }).toMatchObject({ state: "READY_PROTECTED" });
    const members = await request(restartedPage, { path: "/api/members?phone=13990000001", method: "GET" });
    expect(members).toHaveLength(1);
    const recovered = await backupStatus(restartedPage, restartedFactory.id);
    expect(recovered.backupRevision).toBe(recovered.sourceRevision);
    expect(recovered.sourceRevision).toBeGreaterThan(initialStatus.sourceRevision);
    const metadata = JSON.parse(await fs.readFile(path.join(backupRoot, "latest/metadata.json"), "utf8"));
    expect(metadata.revision).toBe(recovered.sourceRevision);
    await closeNormally(restarted, restartedPage, port);
  } finally {
    if (desktop) {
      await killTree(desktop.process().pid).catch(() => {});
      await desktop.close().catch(() => {});
    }
    await fs.rm(userData, { recursive: true, force: true });
    await fs.rm(detachedRoot, { recursive: true, force: true });
  }
});

test("a fresh packaged userData imports an existing backup only after factory login and confirmation", async () => {
  test.setTimeout(240_000);
  const rootDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-packaged-import-"));
  const sourceUserData = path.join(rootDirectory, "source-user-data");
  const freshUserData = path.join(rootDirectory, "fresh-user-data");
  const backupRoot = path.join(rootDirectory, "isolated-backup");
  const phone = "13990000002";
  const sourcePort = await freePort();
  let desktop;
  try {
    desktop = await launchPackaged(environment(sourceUserData, backupRoot, sourcePort));
    const sourcePage = await waitForMemberWindow(desktop);
    const sourceFactory = await loginApi(sourcePage);
    const beforeCreate = await backupStatus(sourcePage, sourceFactory.id);
    await createMember(sourcePage, sourceFactory.id, phone, "重装导入验收会员");
    await expect.poll(async () => {
      const value = await backupStatus(sourcePage, sourceFactory.id);
      return value.state === "READY_PROTECTED"
        && value.sourceRevision > beforeCreate.sourceRevision
        && value.backupRevision === value.sourceRevision;
    }, { timeout: 30_000 }).toBe(true);
    const originalMetadata = JSON.parse(await fs.readFile(path.join(backupRoot, "latest/metadata.json"), "utf8"));
    await closeNormally(desktop, sourcePage, sourcePort);
    desktop = undefined;

    const freshPort = await freePort();
    desktop = await launchPackaged(environment(freshUserData, backupRoot, freshPort));
    const freshPage = await waitForMemberWindow(desktop);
    const diagnostics = await freshPage.evaluate(() => window.memberAdminDesktop.diagnostics());
    expect(diagnostics.backupState).toBe("MAINTENANCE_LOGIN_REQUIRED");
    const untouchedMetadata = JSON.parse(await fs.readFile(path.join(backupRoot, "latest/metadata.json"), "utf8"));
    expect(untouchedMetadata.sha256).toBe(originalMetadata.sha256);

    await loginUi(freshPage);
    await freshPage.getByTestId("admin-nav-settings").click();
    await expect(freshPage.getByTestId("settings-tab-backup")).toBeVisible();
    await freshPage.getByTestId("settings-tab-backup").click();
    const management = freshPage.getByTestId("database-backup-management");
    await management.getByRole("button", { name: "刷新", exact: true }).click();
    const sourceCandidate = management.locator(".backup-candidate-row")
      .filter({ hasText: "会员 1 人" })
      .getByRole("button", { name: "导入此版本", exact: true });
    await expect(sourceCandidate).toBeVisible();
    const freshFactory = await loginApi(freshPage);

    await sourceCandidate.click();
    await expect(freshPage.getByText(/要将版本为 .*最后修改时间为/)).toBeVisible();
    await freshPage.getByRole("button", { name: "取消", exact: true }).click();
    await expect(freshPage.getByTestId("database-import-confirm")).toHaveCount(0);
    expect((await backupStatus(freshPage, freshFactory.id)).state).toBe("MAINTENANCE_LOGIN_REQUIRED");

    await sourceCandidate.click();
    await freshPage.getByTestId("database-import-confirm").click();
    await expect(freshPage.getByTestId("operator-login-page")).toBeVisible({ timeout: 90_000 });
    await loginUi(freshPage);
    const importedFactory = await loginApi(freshPage);
    const importedMembers = await request(freshPage, {
      path: `/api/members?phone=${phone}`,
      method: "GET",
      headers: { "X-Operator-Id": importedFactory.id },
    });
    expect(importedMembers).toHaveLength(1);
    await expect.poll(async () => backupStatus(freshPage, importedFactory.id), { timeout: 30_000 })
      .toMatchObject({ state: "READY_PROTECTED" });
    const importedStatus = await backupStatus(freshPage, importedFactory.id);
    expect(importedStatus.backupRevision).toBe(importedStatus.sourceRevision);
    await closeNormally(desktop, freshPage, freshPort);
    desktop = undefined;
  } finally {
    if (desktop) {
      await killTree(desktop.process().pid).catch(() => {});
      await desktop.close().catch(() => {});
    }
    await fs.rm(rootDirectory, { recursive: true, force: true });
  }
});
