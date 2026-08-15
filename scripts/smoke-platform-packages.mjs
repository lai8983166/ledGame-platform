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

async function smokeMember() {
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-member-smoke-"));
  const port = await freePort();
  const executablePath = await findExe(path.join(root, "release", "member-admin", "win-unpacked"));
  const app = await electron.launch({ executablePath, env: { ...electronEnv, LEDGAME_USER_DATA: userData, LEDGAME_PLATFORM_PORT: String(port) } });
  try {
    const page = await app.firstWindow();
    await page.waitForSelector("#app", { timeout: 30000 });
    await page.waitForFunction(() => window.memberAdminDesktop?.diagnostics().then((value) => value.state === "online"), null, { timeout: 45000 });
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
