const path = require("node:path");
const fs = require("node:fs");
const { app, BrowserWindow, dialog, ipcMain } = require("electron");
const { createProductConfigStore } = require("../shared/config-store.cjs");
const { createApiTransport } = require("../shared/api-transport.cjs");
const { assertPortAvailable, checkHealth, listLanIpv4, validatePort } = require("../shared/network.cjs");
const { createManagedProcess } = require("../shared/managed-process.cjs");
const { resolveMemberAdminResources } = require("./runtime.cjs");
const {
  markImportVerified,
  recoverInterruptedImport,
  replaceDatabase,
  restoreRollback,
} = require("./database-import.cjs");

const projectRoot = path.resolve(__dirname, "../..");
const devUrl = process.env.VITE_MEMBER_ADMIN_DEV_URL;
const concurrencyTestRunId = String(process.env.LEDGAME_CONCURRENCY_TEST_RUN_ID || "").trim() || null;
app.setName("LED Game Member Admin");
if (process.env.LEDGAME_USER_DATA) app.setPath("userData", path.resolve(process.env.LEDGAME_USER_DATA));
const hasSingleInstanceLock = app.requestSingleInstanceLock();
let mainWindow;
let startupWindow;
let store;
let settings = { port: 8090 };
let status = { state: "starting", phase: "STARTING", message: "正在启动本机服务", concurrencyTestRunId, concurrencyTestMode: Boolean(concurrencyTestRunId) };
let backendReady = Promise.resolve();
let transport;
let quitting = false;
const backend = createManagedProcess();

function showExistingWindow() {
  const window = mainWindow && !mainWindow.isDestroyed() ? mainWindow : startupWindow;
  if (!window || window.isDestroyed()) return;
  if (window.isMinimized()) window.restore();
  window.show();
  window.focus();
}

function destroyStartupWindow() {
  if (startupWindow && !startupWindow.isDestroyed()) startupWindow.destroy();
  startupWindow = undefined;
}

function setStatus(next) {
  status = { ...status, ...next };
  mainWindow?.webContents.send("member-admin:status", status);
  startupWindow?.webContents.send("member-admin:startup-status", status);
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
    concurrencyTestRunId,
    concurrencyTestMode: Boolean(concurrencyTestRunId),
  };
}

async function waitForStartupCheck(port, deadline) {
  const localTransport = createApiTransport(async () => `http://127.0.0.1:${port}`, { timeoutMs: 2000 });
  while (Date.now() < deadline) {
    const response = await localTransport({ path: "/api/system/startup-status", method: "GET" });
    if (response.status === 200) {
      const backup = JSON.parse(response.body);
      setStatus({
        state: backup.state === "BLOCKED" ? "failed" : "online",
        backupState: backup.state,
        phase: backup.phase,
        message: backup.message,
        backup,
        lastError: backup.errorCode || null,
      });
      if (backup.state !== "CHECKING") return backup;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw Object.assign(new Error("等待数据库检查完成超时"), { code: "STARTUP_CHECK_TIMEOUT" });
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
    throw Object.assign(new Error(`未找到本机服务 JAR：${resources.serverJar}`), { code: "SERVER_JAR_MISSING" });
  }
  if (path.isAbsolute(resources.javaExecutable) && !fs.existsSync(resources.javaExecutable)) {
    throw Object.assign(new Error(`未找到 Java 运行时：${resources.javaExecutable}`), { code: "JAVA_RUNTIME_MISSING" });
  }
  setStatus({ state: "starting", phase: "STARTING_SERVICE", message: `正在端口 ${validatedPort} 启动本机服务`, lastError: null });
  const backendArguments = [
    "-jar", resources.serverJar,
    "--server.address=0.0.0.0",
    `--server.port=${validatedPort}`,
    `--spring.datasource.url=jdbc:sqlite:${store.dataPath("platform.db")}`,
    `--logging.file.name=${store.logPath("server.log")}`,
  ];
  if (process.env.LEDGAME_DATABASE_BACKUP_ENABLED !== undefined) {
    backendArguments.push(`--ledgame.database-backup.enabled=${process.env.LEDGAME_DATABASE_BACKUP_ENABLED}`);
  }
  if (process.env.LEDGAME_DATABASE_BACKUP_ROOT) {
    backendArguments.push(`--ledgame.database-backup.root-override=${process.env.LEDGAME_DATABASE_BACKUP_ROOT}`);
  }
  if (process.env.LEDGAME_DATABASE_BACKUP_ENVIRONMENT) {
    backendArguments.push(`--ledgame.database-backup.environment=${process.env.LEDGAME_DATABASE_BACKUP_ENVIRONMENT}`);
  }
  backend.start(resources.javaExecutable, backendArguments, {
    cwd: path.dirname(resources.serverJar),
    logPath: store.logPath("server.log"),
    onExit: ({ code, signal }) => {
      if (status.state !== "stopping") {
        setStatus({ state: "failed", phase: "SERVICE_EXITED", message: "本机服务已意外退出", lastError: `exit=${code} signal=${signal}` });
      }
    },
  });
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (!backend.running) throw new Error("本机服务进程已退出");
    try {
      await checkHealth(`http://127.0.0.1:${validatedPort}`, { timeoutMs: 1000 });
      return await waitForStartupCheck(validatedPort, deadline);
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw Object.assign(new Error("等待本机服务启动超时"), { code: "START_TIMEOUT" });
}

async function flushBackend() {
  if (!backend.running || !transport) return true;
  try {
    const response = await transport({ path: "/api/system/database-backup/flush", method: "POST" });
    return response.status === 200 && JSON.parse(response.body).completed === true;
  } catch { return false; }
}

async function stopBackend({ flush = true } = {}) {
  setStatus({ state: "stopping", phase: flush ? "SHUTDOWN_BACKUP" : "STOPPING_SERVICE",
    message: flush ? "正在保存并备份数据" : "正在停止本机服务" });
  if (flush) await flushBackend();
  await backend.stop(15000);
}

async function restartBackend(port) {
  const nextPort = validatePort(port);
  if (nextPort !== settings.port) await assertPortAvailable(nextPort);
  await stopBackend();
  settings = await store.write({ ...settings, port: nextPort });
  backendReady = startBackend(nextPort);
  await backendReady;
  return diagnostics();
}

function parseApiResponse(response) {
  const body = response.body ? JSON.parse(response.body) : null;
  if (response.status < 200 || response.status >= 300) {
    throw Object.assign(new Error(body?.message || `本机服务请求失败（${response.status}）`), {
      code: body?.code || `HTTP_${response.status}`,
    });
  }
  return body;
}

async function importBackupDatabase(candidateId, operatorId) {
  let replacement;
  const databasePath = store.dataPath("platform.db");
  try {
    const prepared = parseApiResponse(await transport({
      path: `/api/database-backup/candidates/${encodeURIComponent(String(candidateId || ""))}/prepare`,
      method: "POST",
      headers: { "X-Operator-Id": operatorId },
    }));
    await ensureStartupWindow();
    startupWindow.show();
    mainWindow.hide();
    setStatus({ state: "stopping", phase: "IMPORT_STOPPING", message: "正在停止本机服务并导入数据库" });
    await stopBackend({ flush: false });
    replacement = replaceDatabase(databasePath, prepared);
    backendReady = startBackend(settings.port);
    const backup = await backendReady;
    if (backup.state === "BLOCKED") throw new Error(backup.message || "导入后的数据库检查失败");
    markImportVerified(databasePath);
    destroyStartupWindow();
    mainWindow.show();
    return { imported: true, revision: prepared.revision, requiresLogin: true };
  } catch (error) {
    if (replacement) {
      try {
        if (backend.running) await stopBackend({ flush: false });
        restoreRollback(databasePath, replacement);
        backendReady = startBackend(settings.port).catch(() => {});
        await backendReady;
      } catch (rollbackError) {
        error.message = `${error.message}；自动回滚失败：${rollbackError.message}`;
      }
    } else if (transport && operatorId) {
      transport({ path: "/api/database-backup/import/cancel", method: "POST",
        headers: { "X-Operator-Id": operatorId } }).catch(() => {});
      if (!backend.running) {
        backendReady = startBackend(settings.port).catch(() => {});
        await backendReady;
      }
    }
    destroyStartupWindow();
    mainWindow?.show();
    throw error;
  }
}

function registerIpc() {
  const fromMainWindow = (event) => event.sender.id === mainWindow?.webContents.id;
  transport = createApiTransport(async () => `http://127.0.0.1:${settings.port}`);
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
    backendReady = startBackend(settings.port);
    await backendReady;
    return diagnostics();
  });
  ipcMain.handle("member-admin:choose-backup-database", async (event, input) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    const result = await dialog.showOpenDialog(mainWindow, {
      title: "选择要导入的数据库备份",
      properties: ["openFile"],
      filters: [{ name: "SQLite 数据库", extensions: ["db", "sqlite", "sqlite3"] }],
    });
    mainWindow.show();
    mainWindow.focus();
    mainWindow.webContents.focus();
    if (result.canceled || result.filePaths.length === 0) return null;
    return parseApiResponse(await transport({
      path: "/api/database-backup/candidates/external",
      method: "POST",
      headers: { "X-Operator-Id": input?.operatorId },
      body: JSON.stringify({ path: result.filePaths[0] }),
    }));
  });
  ipcMain.handle("member-admin:import-backup-database", async (event, input) => {
    if (!fromMainWindow(event)) throw new Error("UNAUTHORIZED_WINDOW");
    return importBackupDatabase(input?.candidateId, input?.operatorId);
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
  mainWindow.on("closed", () => {
    mainWindow = undefined;
    if (!quitting) app.quit();
  });
  if (devUrl) await mainWindow.loadURL(devUrl);
  else await mainWindow.loadFile(path.join(projectRoot, "apps/member-admin/dist/index.html"));
}

async function ensureStartupWindow() {
  if (startupWindow && !startupWindow.isDestroyed()) return startupWindow;
  startupWindow = new BrowserWindow({
    width: 520,
    height: 320,
    resizable: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "startup-preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  startupWindow.on("closed", () => { startupWindow = undefined; });
  await startupWindow.loadFile(path.join(__dirname, "startup.html"));
  startupWindow.webContents.send("member-admin:startup-status", status);
  return startupWindow;
}

if (!hasSingleInstanceLock) app.quit();
app.on("second-instance", showExistingWindow);
if (hasSingleInstanceLock) app.whenReady().then(async () => {
  const defaultPort = Number(process.env.LEDGAME_PLATFORM_PORT || 8090);
  store = createProductConfigStore(app.getPath("userData"), "member-admin", { port: defaultPort });
  settings = await store.read();
  await store.ensureDirectories();
  recoverInterruptedImport(store.dataPath("platform.db"));
  registerIpc();
  await ensureStartupWindow();
  startupWindow.show();
  backendReady = startBackend(settings.port);
  try {
    const backup = await backendReady;
    if (backup.state !== "BLOCKED") {
      await createWindow();
      destroyStartupWindow();
    }
  } catch (error) {
    setStatus({ state: "failed", phase: "START_FAILED", message: error.message, lastError: error.code || "START_FAILED" });
  }
});

app.on("window-all-closed", () => app.quit());
app.on("before-quit", (event) => {
  if (quitting || !backend.running) return;
  event.preventDefault();
  quitting = true;
  Promise.resolve()
    .then(() => ensureStartupWindow())
    .then(() => {
      mainWindow?.hide();
      startupWindow?.show();
      return stopBackend();
    })
    .finally(() => app.exit(0));
});
