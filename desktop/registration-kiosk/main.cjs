const path = require("node:path");
const { app, BrowserWindow, ipcMain } = require("electron");
const { createProductConfigStore } = require("../shared/config-store.cjs");
const { createApiTransport } = require("../shared/api-transport.cjs");
const { buildHttpBaseUrl, checkHealth, validateHost, validatePort } = require("../shared/network.cjs");
const { createKioskLifecycle } = require("./runtime.cjs");

const projectRoot = path.resolve(__dirname, "../..");
const devUrl = process.env.VITE_REGISTRATION_KIOSK_DEV_URL;
const lifecycle = createKioskLifecycle();
let store;
let settings = { host: "127.0.0.1", port: 8090 };
let testedTarget = null;
let operatorWindow;
let kioskWindow;
let healthTimer;
let connectionState = { online: false, code: "NOT_TESTED", message: "尚未测试连接" };

function windowKind(event) {
  if (event.sender.id === operatorWindow?.webContents.id) return "operator";
  if (event.sender.id === kioskWindow?.webContents.id) return "kiosk";
  return "unknown";
}

function requireKind(event, expected) {
  if (windowKind(event) !== expected) throw new Error("UNAUTHORIZED_WINDOW");
}

function broadcastConnection(next) {
  connectionState = next;
  operatorWindow?.webContents.send("registration:connection-state", next);
  kioskWindow?.webContents.send("registration:connection-state", next);
}

function validatedSettings(value) {
  return { host: validateHost(value?.host), port: validatePort(value?.port) };
}

async function testTarget(input) {
  const target = validatedSettings(input);
  try {
    await checkHealth(buildHttpBaseUrl(target.host, target.port), { timeoutMs: 3000 });
    testedTarget = target;
    lifecycle.connectionSucceeded();
    broadcastConnection({ online: true, code: "ONLINE", message: "会员管理端连接正常" });
    return { ok: true, ...target };
  } catch (error) {
    testedTarget = null;
    lifecycle.connectionFailed();
    broadcastConnection({ online: false, code: error.code || "UNREACHABLE", message: "无法连接会员管理端" });
    return { ok: false, code: error.code || "UNREACHABLE", message: error.message };
  }
}

async function loadRenderer(win, kind) {
  const query = `window=${kind}`;
  if (devUrl) await win.loadURL(`${devUrl}?${query}`);
  else await win.loadFile(path.join(projectRoot, "apps/registration-kiosk/dist/index.html"), { query: { window: kind } });
}

async function createOperatorWindow() {
  operatorWindow = new BrowserWindow({
    width: 900, height: 680, minWidth: 760, minHeight: 560, show: false,
    webPreferences: {
      preload: path.join(__dirname, "operator-preload.cjs"),
      contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true,
    },
  });
  operatorWindow.once("ready-to-show", () => operatorWindow.show());
  await loadRenderer(operatorWindow, "operator");
}

async function createKioskWindow() {
  if (kioskWindow && !kioskWindow.isDestroyed()) { kioskWindow.focus(); return; }
  kioskWindow = new BrowserWindow({
    kiosk: true, fullscreen: true, autoHideMenuBar: true, show: false,
    webPreferences: {
      preload: path.join(__dirname, "kiosk-preload.cjs"),
      contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true,
    },
  });
  let staffExitInProgress = false;
  kioskWindow.on("close", (event) => { if (!staffExitInProgress) event.preventDefault(); });
  kioskWindow.webContents.on("before-input-event", (_event, input) => {
    if (input.control && input.shift && input.key === "F12") staffExit();
  });
  kioskWindow.once("ready-to-show", () => {
    kioskWindow.show();
    operatorWindow.hide();
  });
  kioskWindow.once("closed", () => { kioskWindow = null; });
  kioskWindow.__allowStaffExit = () => { staffExitInProgress = true; };
  await loadRenderer(kioskWindow, "kiosk");
  monitorHealth();
}

function staffExit() {
  clearInterval(healthTimer);
  healthTimer = null;
  lifecycle.staffExit();
  if (kioskWindow && !kioskWindow.isDestroyed()) {
    kioskWindow.__allowStaffExit?.();
    kioskWindow.close();
  }
  operatorWindow?.show();
  operatorWindow?.focus();
}

function monitorHealth() {
  clearInterval(healthTimer);
  const run = async () => {
    try {
      await checkHealth(buildHttpBaseUrl(settings.host, settings.port), { timeoutMs: 2500 });
      broadcastConnection({ online: true, code: "ONLINE", message: "服务连接正常" });
    } catch (error) {
      broadcastConnection({ online: false, code: error.code || "UNREACHABLE", message: "服务暂时不可用，请稍候" });
    }
  };
  run();
  healthTimer = setInterval(run, 5000);
}

function registerIpc() {
  const transport = createApiTransport(async () => settings);
  ipcMain.handle("registration:api-request", (event, request) => {
    if (!["operator", "kiosk"].includes(windowKind(event))) throw new Error("UNAUTHORIZED_WINDOW");
    return transport(request);
  });
  ipcMain.handle("registration:read-settings", (event) => {
    requireKind(event, "operator");
    return { ...settings, connectionState };
  });
  ipcMain.handle("registration:save-settings", async (event, input) => {
    requireKind(event, "operator");
    settings = await store.write(validatedSettings(input));
    if (!testedTarget || testedTarget.host !== settings.host || testedTarget.port !== settings.port) {
      lifecycle.connectionFailed();
    }
    return settings;
  });
  ipcMain.handle("registration:test-connection", (event, input) => {
    requireKind(event, "operator");
    return testTarget(input);
  });
  ipcMain.handle("registration:start-kiosk", async (event) => {
    requireKind(event, "operator");
    if (!testedTarget || testedTarget.host !== settings.host || testedTarget.port !== settings.port) {
      throw new Error("请先测试并保存当前管理端地址");
    }
    lifecycle.startKiosk();
    await createKioskWindow();
    return lifecycle.snapshot();
  });
  ipcMain.handle("registration:staff-exit", (event) => {
    requireKind(event, "kiosk");
    staffExit();
  });
}

app.setName("LED Game Registration Kiosk");
if (process.env.LEDGAME_USER_DATA) app.setPath("userData", path.resolve(process.env.LEDGAME_USER_DATA));
app.whenReady().then(async () => {
  store = createProductConfigStore(app.getPath("userData"), "registration-kiosk", { host: "127.0.0.1", port: 8090 });
  settings = await store.read();
  registerIpc();
  await createOperatorWindow();
});

app.on("window-all-closed", () => app.quit());
app.on("before-quit", () => clearInterval(healthTimer));
