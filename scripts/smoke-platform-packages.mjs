import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { _electron as electron } from "playwright";

const root = path.resolve(import.meta.dirname, "..");
const electronEnv = { ...process.env };
delete electronEnv.ELECTRON_RUN_AS_NODE;

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

async function findExe(productDirectory) {
  const entries = await fs.readdir(productDirectory);
  const name = entries.find((entry) => entry.endsWith(".exe"));
  if (!name) throw new Error(`目录包中未找到 exe: ${productDirectory}`);
  return path.join(productDirectory, name);
}

async function waitForWindow(app, selector, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const page of app.windows()) {
      try {
        if (!page.isClosed() && await page.locator(selector).count()) return page;
      } catch {
        // The startup check window is intentionally destroyed when the main window opens.
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`打包应用未在限定时间内打开目标窗口：${selector}`);
}

async function smokeMember() {
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-member-smoke-"));
  const backupRoot = path.join(userData, "database-backup-test");
  const port = await freePort();
  const productDirectory = process.env.LEDGAME_MEMBER_ADMIN_PACKAGE_DIR
    ? path.resolve(process.env.LEDGAME_MEMBER_ADMIN_PACKAGE_DIR)
    : path.join(root, "release", "member-admin", "win-unpacked");
  const executablePath = await findExe(productDirectory);
  const app = await electron.launch({ executablePath, env: { ...electronEnv, LEDGAME_USER_DATA: userData,
    LEDGAME_PLATFORM_PORT: String(port), LEDGAME_DATABASE_BACKUP_ENABLED: "true",
    LEDGAME_DATABASE_BACKUP_ROOT: backupRoot, LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST" } });
  try {
    const page = await waitForWindow(app, "#app");
    await page.waitForFunction(() => window.memberAdminDesktop?.diagnostics().then((value) => value.state === "online"), null, { timeout: 45000 });
    const metadata = JSON.parse(await fs.readFile(path.join(backupRoot, "latest", "metadata.json"), "utf8"));
    if (metadata.format !== "ledgame-platform-backup-v2" || metadata.environment !== "TEST") {
      throw new Error("打包会员管理端未生成隔离的 TEST v2 备份");
    }
  } catch (error) {
    let serverLog = "";
    try { serverLog = await fs.readFile(path.join(userData, "logs", "server.log"), "utf8"); } catch { /* no backend log */ }
    throw new Error(`${error.message}${serverLog ? `\n会员管理端后端日志：\n${serverLog.slice(-4000)}` : "\n会员管理端未生成后端日志。"}`);
  } finally {
    await app.close();
    await fs.rm(userData, { recursive: true, force: true });
  }
}

async function smokeKiosk() {
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-kiosk-smoke-"));
  const executablePath = await findExe(path.join(root, "release", "registration-kiosk", "win-unpacked"));
  const app = await electron.launch({ executablePath, env: { ...electronEnv, LEDGAME_USER_DATA: userData } });
  try {
    const page = await app.firstWindow();
    await page.getByTestId("operator-host").waitFor({ timeout: 30000 });
  } finally {
    await app.close();
    await fs.rm(userData, { recursive: true, force: true });
  }
}

await smokeMember();
await smokeKiosk();
process.stdout.write("两个 Windows 目录包冒烟测试通过。\n");
