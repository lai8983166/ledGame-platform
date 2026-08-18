import { test, expect, _electron as electron } from "@playwright/test";
import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../..");

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

function startHealthServer(port) {
  const server = http.createServer((request, response) => {
    if (request.url === "/api/health") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"status":"ok"}');
      return;
    }
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"code":"NOT_FOUND"}');
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

test("development shell enforces operator/kiosk lifecycle, permissions and reconnect", async () => {
  test.setTimeout(60000);
  const rendererPort = await freePort();
  const apiPort = await freePort();
  const unavailablePort = await freePort();
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-kiosk-electron-"));
  const childEnv = { ...process.env };
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const vite = spawn(process.execPath, [path.join(root, "apps/registration-kiosk/node_modules/vite/bin/vite.js"), "--host", "127.0.0.1", "--port", String(rendererPort)], {
    cwd: path.join(root, "apps/registration-kiosk"), env: childEnv, windowsHide: true, stdio: "ignore",
  });
  let api = await startHealthServer(apiPort);
  let desktop;
  try {
    await waitForUrl(`http://127.0.0.1:${rendererPort}`);
    desktop = await electron.launch({
      args: [path.join(root, "desktop/registration-kiosk/main.cjs")],
      env: { ...childEnv, VITE_REGISTRATION_KIOSK_DEV_URL: `http://127.0.0.1:${rendererPort}`, LEDGAME_USER_DATA: userData },
    });
    const operator = await desktop.firstWindow();
    await expect(operator.getByTestId("operator-host")).toBeVisible();
    expect(desktop.windows()).toHaveLength(1);

    await operator.getByTestId("operator-host").fill("127.0.0.1");
    await operator.getByTestId("operator-port").fill(String(unavailablePort));
    await operator.getByTestId("operator-save").click();
    await operator.getByTestId("operator-test").click();
    await expect(operator.getByTestId("operator-status")).toContainText(/无法|fail|connect/i);
    await expect(operator.getByTestId("operator-launch")).toBeDisabled();

    await operator.getByTestId("operator-port").fill(String(apiPort));
    await operator.getByTestId("operator-save").click();
    await operator.getByTestId("operator-test").click();
    await expect(operator.getByTestId("operator-status")).toContainText(/连接成功|connection/i);
    await operator.getByTestId("operator-launch").click();

    await expect.poll(() => desktop.windows().length).toBe(2);
    const kiosk = desktop.windows().find((page) => page !== operator);
    await expect(kiosk.getByTestId("kiosk-screen-home")).toBeVisible();
    await expect.poll(() => kiosk.evaluate(() => ({
      readSettings: typeof window.registrationDesktop?.readSettings,
      saveSettings: typeof window.registrationDesktop?.saveSettings,
      startKiosk: typeof window.registrationDesktop?.startKiosk,
    }))).toEqual({ readSettings: "undefined", saveSettings: "undefined", startKiosk: "undefined" });

    await desktop.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().find((win) => win.isKiosk())?.close());
    await expect.poll(() => desktop.windows().length).toBe(2);

    await new Promise((resolve) => api.close(resolve));
    await expect(kiosk.getByTestId("kiosk-offline-overlay")).toBeVisible({ timeout: 10000 });
    api = await startHealthServer(apiPort);
    await expect(kiosk.getByTestId("kiosk-offline-overlay")).toBeHidden({ timeout: 10000 });

    await kiosk.getByTestId("kiosk-staff-exit-hotspot").dblclick();
    await expect(kiosk.getByTestId("kiosk-staff-exit-dialog")).toBeVisible();
    const keyboard = kiosk.getByTestId("soft-keyboard");
    await expect(keyboard).toBeVisible();
    for (const key of ["1", "2", "3", "4", "5", "6"]) await keyboard.getByRole("button", { name: key, exact: true }).click();
    await kiosk.getByTestId("kiosk-exit-submit").click();
    await expect(kiosk.getByTestId("kiosk-exit-error")).toContainText(/密码|password/i);
    for (let i = 0; i < 6; i += 1) await keyboard.getByRole("button", { name: "8", exact: true }).click();
    await keyboard.getByRole("button", { name: /Done/i }).click();
    await expect.poll(() => desktop.windows().length).toBe(1);
    await expect(operator.getByTestId("operator-host")).toBeVisible();
  } finally {
    if (desktop) await desktop.close().catch(() => {});
    vite.kill();
    await new Promise((resolve) => api.close(resolve));
    await fs.rm(userData, { recursive: true, force: true });
  }
});
