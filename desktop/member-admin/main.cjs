const path = require("node:path");
const fs = require("node:fs");
const { app, BrowserWindow, ipcMain } = require("electron");
const { createProductConfigStore } = require("../shared/config-store.cjs");
const { createApiTransport } = require("../shared/api-transport.cjs");
const { assertPortAvailable, checkHealth, listLanIpv4, validatePort } = require("../shared/network.cjs");
const { createManagedProcess } = require("../shared/managed-process.cjs");
const { resolveMemberAdminResources } = require("./runtime.cjs");

const projectRoot = path.resolve(__dirname, "../..");
const devUrl = process.env.VITE_MEMBER_ADMIN_DEV_URL;
let mainWindow;
let store;
let settings = { port: 8090 };
let status = { state: "starting", message: "正在启动本机服务" };
let backendReady = Promise.resolve();
const backend = createManagedProcess();

function setStatus(next) {
  status = { ...status, ...next };
  mainWindow?.webContents.send("member-admin:status", status);
}

function diagnostics() {
  const port = settings.port;
  return {
    ...status,
    port,
    dataPath: store.dataPath("platform.db"),
    logPath: store.logPath("server.log"),
    lanUrls: listLanIpv4().map((ip) => `http://${ip}:${port}`),
    recentLogs: backend.tail(),
  };
}

async function startBackend(port) {
  const validatedPort = validatePort(port);
  await store.ensureDirectories();
  await assertPortAvailable(validatedPort);
  const resources = resolveMemberAdminResources({
    packaged: app.isPackaged,
    resourcesPath: process.resourcesPath,
    projectRoot,
  });
  if (!fs.existsSync(resources.serverJar)) {
    const error = Object.assign(new Error(`未找到本机服务 JAR：${resources.serverJar}`), { code: "SERVER_JAR_MISSING" });
    setStatus({ state: "failed", message: error.message, lastError: error.code });
    throw error;
  }
  if (path.isAbsolute(resources.javaExecutable) && !fs.existsSync(resources.javaExecutable)) {
    const error = Object.assign(new Error(`未找到 Java 运行时：${resources.javaExecutable}`), { code: "JAVA_RUNTIME_MISSING" });
    setStatus({ state: "failed", message: error.message, lastError: error.code });
    throw error;
  }
  setStatus({ state: "starting", message: `正在端口 ${validatedPort} 启动本机服务`, lastError: null });
  try {
    backend.start(resources.javaExecutable, [
      "-jar", resources.serverJar,
      `--server.address=0.0.0.0`,
      `--server.port=${validatedPort}`,
      `--spring.datasource.url=jdbc:sqlite:${store.dataPath("platform.db")}`,
      `--logging.file.name=${store.logPath("server.log")}`,
    ], {
      cwd: path.dirname(resources.serverJar),
      logPath: store.logPath("server.log"),
      onExit: ({ code, signal }) => {
        if (status.state !== "stopping") {
          setStatus({ state: "failed", message: "本机服务已意外退出", lastError: `exit=${code} signal=${signal}` });
        }
      },
    });
  } catch (error) {
    setStatus({ state: "failed", message: "无法启动本机服务进程", lastError: error.code || "SPAWN_FAILED" });
    throw error;
  }
  try {
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      if (!backend.running) throw new Error("本机服务进程已退出");
      try {
        await checkHealth(`http://127.0.0.1:${validatedPort}`, { timeoutMs: 1000 });
        setStatus({ state: "online", message: "本机服务运行正常", lastError: null });
        return;
      } catch { await new Promise((resolve) => setTimeout(resolve, 500)); }
    }
    throw Object.assign(new Error("等待本机服务启动超时"), { code: "START_TIMEOUT" });
  } catch (error) {
    setStatus({ state: "failed", message: error.message, lastError: error.code || "START_FAILED" });
    throw error;
  }
}

async function stopBackend() {
  setStatus({ state: "stopping", message: "正在停止本机服务" });
  await backend.stop();
}

async function restartBackend(port) {
  const nextPort = validatePort(port);
  if (nextPort !== settings.port) await assertPortAvailable(nextPort);
  await stopBackend();
  settings = await store.write({ ...settings, port: nextPort });
  backendReady = startBackend(nextPort).catch(() => {});
  await backendReady;
  return diagnostics();
}

function registerIpc() {
  const fromMainWindow = (event) => event.sender.id === mainWindow?.webContents.id;
  const transport = createApiTransport(async () => `http://127.0.0.1:${settings.port}`);
  ipcMain.handle("member-admin:api-request", async (event, request) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    await backendReady;
    return transport(request);
  });
  ipcMain.handle("member-admin:diagnostics", (event) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    return diagnostics();
  });
  ipcMain.handle("member-admin:restart-backend", (event, input) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    return restartBackend(input?.port);
  });
  ipcMain.handle("member-admin:retry-backend", async (event) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    if (backend.running) await stopBackend();
    backendReady = startBackend(settings.port).catch(() => {});
    await backendReady;
    return diagnostics();
  });
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1100,
    minHeight: 700,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });
  mainWindow.once("ready-to-show", () => mainWindow.show());
  if (devUrl) await mainWindow.loadURL(devUrl);
  else await mainWindow.loadFile(path.join(projectRoot, "apps/member-admin/dist/index.html"));
}

app.setName("LED Game Member Admin");
if (process.env.LEDGAME_USER_DATA) app.setPath("userData", path.resolve(process.env.LEDGAME_USER_DATA));
app.whenReady().then(async () => {
  const defaultPort = Number(process.env.LEDGAME_PLATFORM_PORT || 8090);
  store = createProductConfigStore(app.getPath("userData"), "member-admin", { port: defaultPort });
  settings = await store.read();
  registerIpc();
  backendReady = startBackend(settings.port).catch(() => {});
  await createWindow();
});

app.on("window-all-closed", () => app.quit());
app.on("before-quit", (event) => {
  if (status.state === "stopping" || !backend.running) return;
  event.preventDefault();
  stopBackend().finally(() => app.exit(0));
});
