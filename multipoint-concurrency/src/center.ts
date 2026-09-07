import fs from "node:fs/promises";
import path from "node:path";
import net from "node:net";
import { spawn, type ChildProcess } from "node:child_process";
import type { CenterConfig, CenterRunPaths, ConnectionInfo } from "./types.js";
import { FORMAT_VERSION } from "./types.js";
import { generateRunId } from "./config.js";

export async function createUniqueRunId(testRoot: string, date = new Date()): Promise<string> {
  const base = generateRunId(date);
  for (let suffix = 0; suffix < 100; suffix += 1) {
    const candidate = suffix === 0 ? base : `${base}-${String(suffix).padStart(2, "0")}`;
    if (!await fs.stat(path.join(testRoot, candidate)).then(() => true).catch(() => false)) return candidate;
  }
  throw new Error("同一时间批次过多，无法生成唯一 runId");
}

export function createCenterRunPaths(config: CenterConfig): CenterRunPaths {
  const runRoot = path.resolve(config.testRoot, config.runId);
  if (!runRoot.startsWith(`${path.resolve(config.testRoot)}${path.sep}`)) {
    throw new Error("runId 生成的运行目录超出测试根目录");
  }
  return {
    runRoot,
    userData: path.join(runRoot, "member-admin"),
    backupRoot: path.join(runRoot, "backup"),
    centerLog: path.join(runRoot, "member-admin", "logs", "server.log"),
    connectionFile: path.join(runRoot, "connection.json"),
  };
}

async function platformHealthy(baseUrl: string, fetchImpl: typeof fetch): Promise<boolean> {
  try {
    const response = await fetchImpl(`${baseUrl}/api/health`, { signal: AbortSignal.timeout(800) });
    return response.ok;
  } catch { return false; }
}

async function platformReady(baseUrl: string, fetchImpl: typeof fetch): Promise<boolean> {
  if (!await platformHealthy(baseUrl, fetchImpl)) return false;
  try {
    const activationResponse = await fetchImpl(`${baseUrl}/api/system/activation`, { signal: AbortSignal.timeout(6000) });
    if (activationResponse.ok) {
      const activation = await activationResponse.json() as { activated?: boolean; message?: string };
      if (activation.activated === false) throw new Error(`会员管理端尚未激活：${activation.message ?? "请提供本机有效授权"}`);
    }
    const response = await fetchImpl(`${baseUrl}/api/system/startup-status`, { signal: AbortSignal.timeout(1000) });
    if (!response.ok) return false;
    const value = await response.json() as { state?: string; message?: string };
    if (value.state === "BLOCKED") throw new Error(`测试数据库启动检查被阻止：${value.message ?? "原因未知"}`);
    return Boolean(value.state && value.state !== "CHECKING");
  } catch (error) {
    if (error instanceof Error && error.message.startsWith("测试数据库启动检查被阻止")) throw error;
    if (error instanceof Error && error.message.startsWith("会员管理端尚未激活")) throw error;
    return false;
  }
}

export async function isPortAvailable(port: number, host = "0.0.0.0"): Promise<boolean> {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.unref();
    server.once("error", () => resolve(false));
    server.listen({ port, host }, () => server.close(() => resolve(true)));
  });
}

export interface CenterDependencies {
  fetchImpl?: typeof fetch;
  portAvailable?: (port: number) => Promise<boolean>;
  spawnProcess?: (executable: string, env: NodeJS.ProcessEnv) => ChildProcess;
  now?: () => Date;
}

export async function preflightCenter(config: CenterConfig, dependencies: CenterDependencies = {}): Promise<CenterRunPaths> {
  const executable = await fs.stat(config.memberAdminExecutable).catch(() => null);
  if (!executable?.isFile()) throw new Error(`未找到会员管理端程序：${config.memberAdminExecutable}`);
  if (await platformHealthy(config.normalPlatformUrl, dependencies.fetchImpl ?? fetch)) {
    throw new Error(`正常会员管理端仍在运行：${config.normalPlatformUrl}，请先正常关闭`);
  }
  if (!await (dependencies.portAvailable ?? isPortAvailable)(config.testPort)) {
    throw new Error(`测试端口 ${config.testPort} 已被占用`);
  }
  const paths = createCenterRunPaths(config);
  await fs.mkdir(paths.userData, { recursive: true });
  await fs.mkdir(paths.backupRoot, { recursive: true });
  const probe = path.join(paths.runRoot, ".write-probe");
  await fs.writeFile(probe, "ok", "utf8");
  await fs.rm(probe);
  return paths;
}

function defaultSpawn(executable: string, env: NodeJS.ProcessEnv): ChildProcess {
  const child = spawn(executable, [], { env, detached: true, stdio: "ignore", windowsHide: false });
  child.unref();
  return child;
}

async function waitForCenter(baseUrl: string, timeoutMs: number, child: ChildProcess, fetchImpl: typeof fetch): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`会员管理端提前退出，exit=${child.exitCode}`);
    if (await platformReady(baseUrl, fetchImpl)) return;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`等待会员管理端测试服务启动超时（${timeoutMs}ms）`);
}

export async function startCenter(config: CenterConfig, dependencies: CenterDependencies = {}): Promise<ConnectionInfo> {
  if (!config.activationLicensePath) throw new Error("并发测试需要本机有效授权，请在 center 配置中设置 activationLicensePath（不影响离线计划预览）");
  const licenseInfo = await fs.stat(config.activationLicensePath).catch(() => null);
  if (!licenseInfo?.isFile() || licenseInfo.size > 16640) throw new Error("测试授权文件不存在或大小不正确");
  const paths = await preflightCenter(config, dependencies);
  const licenseDirectory = path.join(paths.userData, "activation");
  await fs.mkdir(licenseDirectory, { recursive: true });
  await fs.copyFile(config.activationLicensePath, path.join(licenseDirectory, "license.json"), fs.constants.COPYFILE_EXCL);
  const localBaseUrl = `http://127.0.0.1:${config.testPort}`;
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    LEDGAME_USER_DATA: paths.userData,
    LEDGAME_PLATFORM_PORT: String(config.testPort),
    LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST",
    LEDGAME_DATABASE_BACKUP_ROOT: paths.backupRoot,
    LEDGAME_CONCURRENCY_TEST_RUN_ID: config.runId,
  };
  const child = (dependencies.spawnProcess ?? defaultSpawn)(config.memberAdminExecutable, env);
  await waitForCenter(localBaseUrl, config.startupTimeoutMs, child, dependencies.fetchImpl ?? fetch);
  const connection: ConnectionInfo = {
    formatVersion: FORMAT_VERSION,
    runId: config.runId,
    platformBaseUrl: `http://${config.lanHost}:${config.testPort}`,
    testPort: config.testPort,
    centerLogPath: paths.centerLog,
    runRoot: paths.runRoot,
    generatedAt: (dependencies.now ?? (() => new Date()))().toISOString(),
    safetyConfirmation: config.safetyConfirmation,
  };
  await fs.writeFile(paths.connectionFile, `${JSON.stringify(connection, null, 2)}\n`, "utf8");
  return connection;
}
