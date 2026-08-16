import { _electron as electron, chromium, expect, type Browser, type ElectronApplication, type Page, type TestInfo } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  ManagedProcessRegistry,
  allocateLoopbackPort,
  createOwnedRunDirectory,
  removeOwnedRunDirectory,
  spawnManagedProcess,
  waitForReadiness,
  type ManagedChildProcess,
} from "./runtime";
import { BidirectionalFloorDevice } from "./bidirectionalFloorDevice";

type MemberFixture = { phone: string; name: string; uid: string };
type RuntimePorts = { platform: number; admin: number; kiosk: number; game: number; renderer: number; debugTcp: number; floorTcp: number };
export type StoreAcceptanceOptions = { runtimeMode?: "SIMULATION" | "PRODUCTION"; platformClients?: "browser" | "desktop" };
type ResolvedStoreAcceptanceOptions = { runtimeMode: "SIMULATION" | "PRODUCTION"; platformClients: "browser" | "desktop" };

const platformRoot = path.resolve(process.cwd());
const gameRoot = path.resolve(platformRoot, "..", "ledGame");
const gameBackendRoot = path.resolve(platformRoot, "..", "ledGame-backend");
const runtimeBase = path.resolve(platformRoot, "acceptance", ".runtime");

async function httpOk(url: string): Promise<boolean> {
  const response = await fetch(url);
  return response.ok;
}

function safeRunId(runDirectory: string): string {
  return path.basename(runDirectory).replace(/[^a-zA-Z0-9_-]/g, "_");
}

function processNameInChinese(label: string): string {
  if (label.startsWith("platform-restart-")) return `会员管理后端重启进程-${label.slice("platform-restart-".length)}`;
  if (label.startsWith("game-backend-restart-")) return `游戏后端重启进程-${label.slice("game-backend-restart-".length)}`;
  return ({
    platform: "会员管理后端",
    "member-admin": "会员管理端",
    "registration-kiosk": "自助注册端",
    "game-backend": "游戏后端",
    "game-renderer": "游戏前端",
  } as Record<string, string>)[label] || label;
}

export class StoreAcceptanceHarness {
  readonly #testInfo: TestInfo;
  readonly #runDirectory: string;
  readonly #ports: RuntimePorts;
  readonly #options: ResolvedStoreAcceptanceOptions;
  readonly #processes = new ManagedProcessRegistry();
  readonly #children: ManagedChildProcess[] = [];
  #platformProcess: ManagedChildProcess | null = null;
  #gameBackendProcess: ManagedChildProcess | null = null;
  #platformStartCount = 0;
  #gameBackendStartCount = 0;
  #browser: Browser | null = null;
  #adminPage: Page | null = null;
  #kioskPage: Page | null = null;
  #electronApp: ElectronApplication | null = null;
  #memberAdminElectron: ElectronApplication | null = null;
  #registrationElectron: ElectronApplication | null = null;
  #registrationOperatorPage: Page | null = null;
  #mainPage: Page | null = null;
  #touchPage: Page | null = null;
  #debugPage: Page | null = null;
  #floorDevice: BidirectionalFloorDevice | null = null;
  #stopped = false;

  private constructor(testInfo: TestInfo, runDirectory: string, ports: RuntimePorts, options: ResolvedStoreAcceptanceOptions) {
    this.#testInfo = testInfo;
    this.#runDirectory = runDirectory;
    this.#ports = ports;
    this.#options = options;
  }

  static async start(testInfo: TestInfo, options: StoreAcceptanceOptions = {}): Promise<StoreAcceptanceHarness> {
    const runDirectory = await createOwnedRunDirectory(runtimeBase);
    const ports: RuntimePorts = {
      platform: await allocateLoopbackPort(),
      admin: await allocateLoopbackPort(),
      kiosk: await allocateLoopbackPort(),
      game: await allocateLoopbackPort(),
      renderer: await allocateLoopbackPort(),
      debugTcp: await allocateLoopbackPort(),
      floorTcp: await allocateLoopbackPort(),
    };
    const resolvedOptions: ResolvedStoreAcceptanceOptions = {
      runtimeMode: options.runtimeMode === "PRODUCTION" ? "PRODUCTION" : "SIMULATION",
      platformClients: options.platformClients === "desktop" ? "desktop" : "browser",
    };
    const harness = new StoreAcceptanceHarness(testInfo, runDirectory, ports, resolvedOptions);
    try {
      await harness.#startAll();
      return harness;
    } catch (error) {
      await harness.#attachDiagnostics("启动失败");
      await harness.stop(false);
      throw error;
    }
  }

  async #startAll(): Promise<void> {
    const logs = path.join(this.#runDirectory, "logs");
    const electronUserData = path.join(this.#runDirectory, "electron-user-data");
    await mkdir(path.join(electronUserData, "settings"), { recursive: true });
    await mkdir(logs, { recursive: true });
    if (this.#options.runtimeMode === "PRODUCTION") {
      this.#floorDevice = new BidirectionalFloorDevice(this.#ports.floorTcp, 16, 16);
      await this.#floorDevice.start();
      this.#processes.add({ label: "bidirectional-floor", stop: () => this.#floorDevice!.stop() });
    }
    await writeFile(path.join(electronUserData, "settings", "application.json"), `${JSON.stringify({
      entryMethod: "wristband",
      mode: this.#options.runtimeMode === "PRODUCTION" ? "game" : "debug",
      memberPlatformHost: "127.0.0.1",
      memberPlatformPort: this.#ports.platform,
      secondaryDisplay: null,
    }, null, 2)}\n`, "utf8");
    await writeFile(path.join(this.#runDirectory, "configuration.json"), `${JSON.stringify({
      schemaVersion: 1,
      repositories: { platformRoot, gameRoot, gameBackendRoot },
      ports: this.#ports,
      runtimeMode: this.#options.runtimeMode,
      browserChannel: process.env.ACCEPTANCE_BROWSER_CHANNEL || "msedge",
      headed: process.env.ACCEPTANCE_HEADED === "1",
    }, null, 2)}\n`, "utf8");

    if (this.#options.platformClients === "browser") await this.#startPlatform();
    this.#startChild("member-admin", "pnpm", ["--dir", path.join(platformRoot, "apps", "member-admin"), "exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.admin), "--strictPort"], platformRoot, {
      VITE_PLATFORM_BASE_URL: this.platformBaseUrl,
    });
    await this.#ready("Member Admin", `http://127.0.0.1:${this.#ports.admin}/`, this.#children.at(-1)!);

    this.#startChild("registration-kiosk", "pnpm", ["--dir", path.join(platformRoot, "apps", "registration-kiosk"), "exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.kiosk), "--strictPort"], platformRoot, {
      VITE_PLATFORM_BASE_URL: this.platformBaseUrl,
    });
    await this.#ready("Registration Kiosk", `http://127.0.0.1:${this.#ports.kiosk}/`, this.#children.at(-1)!);

    if (this.#options.platformClients === "desktop") await this.#startPlatformDesktopClients(electronUserData);

    await this.#startGameBackend();
    const seed = await fetch(`${this.gameBaseUrl}/dev/seed/simple-variants`, { method: "POST" });
    if (!seed.ok) throw new Error(`Game seed failed with HTTP ${seed.status}: ${await seed.text()}`);

    this.#startChild("game-renderer", "pnpm", ["exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.renderer), "--strictPort"], gameRoot);
    await this.#ready("Game renderer", `http://127.0.0.1:${this.#ports.renderer}/`, this.#children.at(-1)!);

    if (this.#options.platformClients === "browser") {
      this.#browser = await chromium.launch({
        channel: process.env.ACCEPTANCE_BROWSER_CHANNEL || "msedge",
        headless: process.env.ACCEPTANCE_HEADED !== "1",
      });
      const adminContext = await this.#browser.newContext({ viewport: { width: 1440, height: 1000 } });
      const kioskContext = await this.#browser.newContext({ viewport: { width: 1440, height: 1000 } });
      this.#adminPage = await adminContext.newPage();
      this.#kioskPage = await kioskContext.newPage();
      await Promise.all([
        this.#adminPage.goto(`http://127.0.0.1:${this.#ports.admin}/`, { waitUntil: "domcontentloaded" }),
        this.#kioskPage.goto(`http://127.0.0.1:${this.#ports.kiosk}/`, { waitUntil: "domcontentloaded" }),
      ]);
    }
    await expect(this.#adminPage.getByTestId("admin-page-wristbands")).toBeVisible();
    await expect(this.#kioskPage.getByTestId("kiosk-screen-home")).toBeVisible();

    const { ELECTRON_RUN_AS_NODE: _electronRunAsNode, ...electronEnvironment } = process.env;
    this.#electronApp = await electron.launch({
      executablePath: path.join(gameRoot, "node_modules", "electron", "dist", "electron.exe"),
      args: [gameRoot],
      cwd: gameRoot,
      env: {
        ...electronEnvironment,
        VITE_DEV_SERVER_URL: `http://127.0.0.1:${this.#ports.renderer}`,
        LED_BACKEND_URL: this.gameBaseUrl,
        LED_USER_DATA_DIR: electronUserData,
        LED_DISABLE_DEVTOOLS: "1",
        LED_DEBUG_TCP_PORT: String(this.#ports.debugTcp),
        LED_RUNTIME_STATE_THROTTLE_MS: "20",
      },
    });
    await expect.poll(() => this.#electronApp!.windows().some((page) => page.url().startsWith(`http://127.0.0.1:${this.#ports.renderer}`)), { timeout: 20_000 }).toBe(true);
    this.#mainPage = this.#electronApp.windows().find((page) => page.url().startsWith(`http://127.0.0.1:${this.#ports.renderer}`) && !page.url().includes("window=")) ?? null;
    if (!this.#mainPage) throw new Error(`Electron main window missing: ${this.#electronApp.windows().map((page) => page.url()).join(", ")}`);
    await expect(this.#mainPage.getByTestId("game-enter-flow")).toBeVisible();
  }

  async #startPlatformDesktopClients(electronUserData: string): Promise<void> {
    const electronExecutable = path.join(platformRoot, "node_modules", "electron", "dist", "electron.exe");
    const { ELECTRON_RUN_AS_NODE: _electronRunAsNode, ...electronEnvironment } = process.env;
    this.#memberAdminElectron = await electron.launch({
      executablePath: electronExecutable,
      args: [path.join(platformRoot, "desktop", "member-admin", "main.cjs")],
      cwd: platformRoot,
      env: {
        ...electronEnvironment,
        VITE_MEMBER_ADMIN_DEV_URL: `http://127.0.0.1:${this.#ports.admin}`,
        LEDGAME_USER_DATA: path.join(electronUserData, "member-admin"),
        LEDGAME_PLATFORM_PORT: String(this.#ports.platform),
      },
    });
    this.#adminPage = await this.#memberAdminElectron.firstWindow();
    await expect.poll(async () => {
      try { return await httpOk(`${this.platformBaseUrl}/api/health`); } catch { return false; }
    }, { timeout: 45_000 }).toBe(true);

    this.#registrationElectron = await electron.launch({
      executablePath: electronExecutable,
      args: [path.join(platformRoot, "desktop", "registration-kiosk", "main.cjs")],
      cwd: platformRoot,
      env: {
        ...electronEnvironment,
        VITE_REGISTRATION_KIOSK_DEV_URL: `http://127.0.0.1:${this.#ports.kiosk}`,
        LEDGAME_USER_DATA: path.join(electronUserData, "registration-kiosk"),
      },
    });
    this.#registrationOperatorPage = await this.#registrationElectron.firstWindow();
    await this.#registrationOperatorPage.getByTestId("operator-host").fill("127.0.0.1");
    await this.#registrationOperatorPage.getByTestId("operator-port").fill(String(this.#ports.platform));
    await this.#registrationOperatorPage.getByTestId("operator-save").click();
    await this.#registrationOperatorPage.getByTestId("operator-test").click();
    await expect(this.#registrationOperatorPage.getByTestId("operator-status")).toContainText(/连接成功|connection/i);
    await this.#registrationOperatorPage.getByTestId("operator-launch").click();
    await expect.poll(() => this.#registrationElectron!.windows().length).toBe(2);
    this.#kioskPage = this.#registrationElectron.windows().find((page) => page !== this.#registrationOperatorPage) ?? null;
    if (!this.#kioskPage) throw new Error("Registration kiosk customer window is missing");
  }

  async exitRegistrationKioskToOperator(): Promise<void> {
    if (!this.#registrationElectron || !this.#registrationOperatorPage || !this.#kioskPage) throw new Error("Registration desktop is not started");
    await this.#kioskPage.evaluate(() => window.registrationDesktop?.staffExit?.("888888"));
    await expect.poll(() => this.#registrationElectron!.windows().length).toBe(1);
    await expect(this.#registrationOperatorPage.getByTestId("operator-host")).toBeVisible();
  }

  async #startGameBackend(): Promise<void> {
    const runId = safeRunId(this.#runDirectory).replaceAll("-", "_");
    const label = this.#gameBackendStartCount === 0 ? "game-backend" : `game-backend-restart-${this.#gameBackendStartCount}`;
    this.#gameBackendStartCount += 1;
    const databasePath = path.join(this.#runDirectory, "game-runtime").replaceAll("\\", "/");
    const productionEnvironment: NodeJS.ProcessEnv = this.#options.runtimeMode === "PRODUCTION" ? {
      ACCEPTANCE_FAKE_HARDWARE_READINESS: "true",
      SPRING_APPLICATION_JSON: JSON.stringify({
        led: {
          outputs: [
            { name: "bridge", enabled: false, host: "127.0.0.1", port: 3001 },
            { name: "debug-panel", enabled: false, host: "127.0.0.1", port: this.#ports.debugTcp },
            {
              name: "elc408-sdk",
              enabled: true,
              host: "127.0.0.1",
              port: this.#ports.floorTcp,
              queueCapacity: 4,
              connectTimeoutMillis: 500,
              inputEnabled: true,
            },
          ],
        },
      }),
    } : {};
    this.#gameBackendProcess = this.#startChild(label, "mvn", ["-q", "spring-boot:run"], gameBackendRoot, {
      SPRING_PROFILES_ACTIVE: "acceptance",
      ACCEPTANCE_GAME_DATABASE_URL: `jdbc:h2:file:${databasePath};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE`,
      ACCEPTANCE_GAME_PORT: String(this.#ports.game),
      MEMBER_PLATFORM_BASE_URL: this.platformBaseUrl,
      LEDGAME_DEVICE_ID: `acceptance-${runId}`,
      LEDGAME_ROOM_ID: `acceptance-${runId}`,
      LEDGAME_ROOM_NAME: "Acceptance Room",
      LED_ROOM_RECONNECT_DELAY: "250ms",
      ...productionEnvironment,
    });
    await this.#ready("Game backend", `${this.gameBaseUrl}/engine/demo/state`, this.#gameBackendProcess, 120_000);
  }

  #startChild(label: string, command: string, args: string[], cwd: string, env: NodeJS.ProcessEnv = {}): ManagedChildProcess {
    const child = spawnManagedProcess({
      label,
      command,
      args,
      cwd,
      env,
      logFile: path.join(this.#runDirectory, "logs", `${label}.log`),
    });
    this.#children.push(child);
    this.#processes.add(child);
    return child;
  }

  async #startPlatform(clockOffsetSeconds = 0): Promise<void> {
    const label = this.#platformStartCount === 0 ? "platform" : `platform-restart-${this.#platformStartCount}`;
    this.#platformStartCount += 1;
    this.#platformProcess = this.#startChild(label, "mvn", ["-q", "-f", path.join(platformRoot, "server", "pom.xml"), "spring-boot:run"], platformRoot, {
      SPRING_PROFILES_ACTIVE: "acceptance",
      ACCEPTANCE_PLATFORM_DB_PATH: path.join(this.#runDirectory, "platform.db"),
      ACCEPTANCE_PLATFORM_PORT: String(this.#ports.platform),
      ACCEPTANCE_CLOCK_OFFSET_SECONDS: String(clockOffsetSeconds),
    });
    await this.#ready("Platform server", `${this.platformBaseUrl}/api/health`, this.#platformProcess, 90_000);
  }

  async #ready(label: string, url: string, processHandle: ManagedChildProcess, timeoutMs = 45_000): Promise<void> {
    await waitForReadiness({ label, timeoutMs, hasExited: processHandle.hasExited, probe: () => httpOk(url) });
  }

  get platformBaseUrl(): string { return `http://127.0.0.1:${this.#ports.platform}`; }
  get gameBaseUrl(): string { return `http://127.0.0.1:${this.#ports.game}`; }
  get adminPage(): Page { if (!this.#adminPage) throw new Error("Member Admin is not started"); return this.#adminPage; }
  get kioskPage(): Page { if (!this.#kioskPage) throw new Error("Registration Kiosk is not started"); return this.#kioskPage; }
  get touchPage(): Page { if (!this.#touchPage) throw new Error("Game Touch is not started"); return this.#touchPage; }
  get debugPage(): Page { if (!this.#debugPage) throw new Error("Debug Panel is not started"); return this.#debugPage; }

  async chargeWristband(uid: string, minutes: number): Promise<void> {
    const page = this.adminPage;
    const uidInput = page.getByTestId("admin-charge-uid");
    await uidInput.click();
    await uidInput.fill("");
    await page.keyboard.type(uid);
    await page.getByTestId("admin-charge-minutes").fill(String(minutes));
    await uidInput.press("Enter");
    const row = page.getByTestId(`admin-wristband-${uid}`);
    await expect(row).toBeVisible();
    await expect(row.getByTestId("admin-wristband-status")).toHaveAttribute("data-status", "charged");
  }

  async registerAndBind(member: MemberFixture): Promise<void> {
    await this.#registerUntilSwipe(member);
    const page = this.kioskPage;
    await this.#scanKioskWristband(member.uid);
    await expect(page.getByTestId("kiosk-bind-success")).toBeVisible();
  }

  async #registerUntilSwipe(member: MemberFixture): Promise<void> {
    const page = this.kioskPage;
    await page.goto(`http://127.0.0.1:${this.#ports.kiosk}/`, { waitUntil: "domcontentloaded" });
    await page.getByTestId("kiosk-activate").click();
    await page.getByTestId("kiosk-member-phone").fill(member.phone);
    await page.getByTestId("kiosk-member-submit").click();
    await expect(page.getByTestId("kiosk-screen-register")).toBeVisible();
    await page.getByTestId("kiosk-registration-avatar").click();
    await page.getByTestId("kiosk-avatar-library-open").click();
    await page.getByTestId("kiosk-avatar-nova").click();
    await page.getByTestId("kiosk-avatar-confirm").click();
    await page.getByTestId("kiosk-registration-name").fill(member.name);
    await page.getByTestId("kiosk-registration-birth-year").fill("1990");
    await page.getByTestId("kiosk-registration-birth-month").fill("01");
    await page.getByTestId("kiosk-registration-birth-day").fill("02");
    await page.getByTestId("kiosk-registration-gender-secret").click();
    await page.getByTestId("kiosk-registration-submit").click();
    await expect(page.getByTestId("kiosk-screen-swipe")).toBeVisible();
  }

  async #scanKioskWristband(uid: string): Promise<void> {
    const page = this.kioskPage;
    const scan = page.getByTestId("kiosk-wristband-uid");
    await scan.click();
    await page.keyboard.type(uid);
    await page.keyboard.press("Enter");
  }

  async expectDuplicateBindingRejected(member: MemberFixture, existingUid: string): Promise<void> {
    const beforeResponse = await fetch(`${this.platformBaseUrl}/api/wristbands/${existingUid}`);
    if (!beforeResponse.ok) throw new Error(`Wristband query failed with HTTP ${beforeResponse.status}`);
    const before = await beforeResponse.json() as { bindingId?: number; memberId?: number; status?: string };
    await this.#registerUntilSwipe(member);
    await this.#scanKioskWristband(existingUid);
    await expect(this.kioskPage.getByTestId("kiosk-bind-error")).toBeVisible();
    await expect(this.kioskPage.getByTestId("kiosk-bind-error")).toContainText("此手环已绑定");
    const afterResponse = await fetch(`${this.platformBaseUrl}/api/wristbands/${existingUid}`);
    if (!afterResponse.ok) throw new Error(`Wristband query failed with HTTP ${afterResponse.status}`);
    const after = await afterResponse.json() as { bindingId?: number; memberId?: number; status?: string };
    expect({ bindingId: after.bindingId, memberId: after.memberId, status: after.status })
      .toEqual({ bindingId: before.bindingId, memberId: before.memberId, status: before.status });
  }

  async startGame(uid: string): Promise<void> {
    await this.#openGamePreparation();
    await this.#scanGameWristband(uid);
    await expect(this.touchPage.getByTestId("game-player-access")).toHaveAttribute("data-wristband-uid", uid);
    if (this.#options.runtimeMode === "PRODUCTION") {
      await this.touchPage.getByTestId("game-player-next").click();
      await expect(this.touchPage.getByTestId("game-game-next")).toBeEnabled();
      await this.touchPage.getByTestId("game-game-next").click();
    } else {
      const game = this.touchPage.locator('[data-testid^="game-option-"]').first();
      await expect(game).toBeVisible();
      await game.click();
    }
    await expect(this.touchPage.getByTestId("game-start")).toBeEnabled();
    await this.touchPage.getByTestId("game-start").click();
    await expect(this.touchPage.getByTestId("game-touch")).toHaveAttribute("data-state", "RUNNING", { timeout: 30_000 });
  }

  async #openGamePreparation(): Promise<void> {
    if (!this.#mainPage || !this.#electronApp) throw new Error("Electron is not started");
    const needsDebugPanel = this.#options.runtimeMode === "SIMULATION";
    if (!this.#touchPage || (needsDebugPanel && !this.#debugPage)) {
      await this.#mainPage.getByTestId("game-enter-flow").click();
      await expect.poll(() => this.#electronApp!.windows().length, { timeout: 20_000 }).toBeGreaterThanOrEqual(needsDebugPanel ? 3 : 2);
      const windows = this.#electronApp.windows();
      this.#touchPage = windows.find((page) => page.url().includes("window=touch")) ?? null;
      this.#debugPage = windows.find((page) => page.url().includes("window=debug")) ?? null;
      if (!this.#touchPage || (needsDebugPanel && !this.#debugPage)) {
        throw new Error(`Electron auxiliary windows missing: ${windows.map((page) => page.url()).join(", ")}`);
      }
    }
    if (await this.currentEngineState() === "STOPPED") {
      await this.touchPage.getByTestId("game-return-idle").click();
    }
    await expect(this.touchPage.getByTestId("game-touch")).toHaveAttribute("data-state", "IDLE");
    await this.touchPage.getByTestId("game-touch-idle").click();
    await expect(this.touchPage.getByTestId("game-touch")).toHaveAttribute("data-state", "PREPARING");
    await expect(this.touchPage.getByTestId("game-wristband-prompt")).toBeVisible();
  }

  async #scanGameWristband(uid: string): Promise<void> {
    if (!this.#electronApp) throw new Error("Electron is not started");
    await this.touchPage.bringToFront();
    await this.#electronApp.evaluate(async ({ BrowserWindow }, wristbandUid) => {
      const target = BrowserWindow.getAllWindows().find((window) => window.webContents.getURL().includes("window=touch"));
      if (!target) throw new Error("Touch BrowserWindow is unavailable for keyboard scan");
      target.focus();
      target.webContents.focus();
      for (const keyCode of [...wristbandUid, "Enter"]) {
        target.webContents.sendInputEvent({ type: "keyDown", keyCode });
        target.webContents.sendInputEvent({ type: "keyUp", keyCode });
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
    }, uid);
  }

  async currentEngineState(): Promise<string> {
    return (await this.touchPage.getByTestId("game-touch").getAttribute("data-state")) || "UNKNOWN";
  }

  async currentRuntimeMode(): Promise<string> {
    const response = await fetch(`${this.gameBaseUrl}/engine/game/state`);
    if (!response.ok) throw new Error(`Runtime state query failed with HTTP ${response.status}`);
    const body = await response.json() as { data?: { runtimeMode?: string } };
    return body.data?.runtimeMode || "UNKNOWN";
  }

  async enqueueNextGame(uid: string): Promise<void> {
    await this.touchPage.getByTestId("game-queue-open").click();
    await expect(this.touchPage.getByTestId("game-queue-dialog")).toBeVisible();
    await this.#scanGameWristband(uid);
    await expect(this.touchPage.getByTestId("game-queue-uid")).toHaveValue(uid);
    await this.touchPage.getByTestId("game-queue-submit").click();
    await expect.poll(() => this.waitingQueueLength()).toBe(1);
    await this.touchPage.getByTestId("game-queue-open").click();
    await expect(this.touchPage.getByTestId(`game-queue-item-${uid}`)).toBeVisible();
    await this.touchPage.getByTestId("game-queue-close").click();
  }

  async enqueueSameGameAgain(uid: string): Promise<void> {
    await this.touchPage.getByTestId("game-queue-open").click();
    await expect(this.touchPage.getByTestId("game-queue-dialog")).toBeVisible();
    await this.#scanGameWristband(uid);
    await expect(this.touchPage.getByTestId("game-queue-uid")).toHaveValue(uid);
    await this.touchPage.getByTestId("game-queue-submit").click();
    await expect.poll(() => this.waitingQueueLength()).toBe(1);
  }

  async activateWristbandThroughPublicApi(uid: string): Promise<void> {
    const response = await fetch(`${this.platformBaseUrl}/api/game-access/activate`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ uid, deviceId: "acceptance-setup", roomId: "acceptance-room" }),
    });
    if (!response.ok) throw new Error(`Wristband activation failed with HTTP ${response.status}: ${await response.text()}`);
  }

  async restartPlatform(clockOffsetSeconds = 0): Promise<void> {
    await this.stopPlatform();
    await this.#startPlatform(clockOffsetSeconds);
  }

  async stopPlatform(): Promise<void> {
    if (!this.#platformProcess) throw new Error("Platform server is not started");
    await this.#platformProcess.stop();
    this.#platformProcess = null;
    await expect.poll(async () => {
      try { return await httpOk(`${this.platformBaseUrl}/api/health`); } catch { return false; }
    }, { timeout: 15_000 }).toBe(false);
  }

  async startPlatform(): Promise<void> {
    if (this.#platformProcess) throw new Error("Platform server is already started");
    await this.#startPlatform();
  }

  async restartGameBackend(): Promise<void> {
    if (!this.#gameBackendProcess) throw new Error("Game backend is not started");
    await this.#gameBackendProcess.stop();
    this.#gameBackendProcess = null;
    await expect.poll(async () => {
      try { return await httpOk(`${this.gameBaseUrl}/engine/demo/state`); } catch { return false; }
    }, { timeout: 15_000 }).toBe(false);
    await this.#startGameBackend();
  }

  async expectGameAdmissionRejected(uid: string, expectedCode: string): Promise<void> {
    await this.#openGamePreparation();
    await this.#scanGameWristband(uid);
    await expect(this.touchPage.getByTestId("game-error")).toBeVisible();
    await expect(this.touchPage.getByTestId("game-player-access")).toHaveCount(0);
    const response = await fetch(`${this.platformBaseUrl}/api/game-access/activate`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ uid, deviceId: "acceptance-assertion", roomId: "acceptance-room" }),
    });
    expect(response.status).toBe(409);
    expect(await response.text()).toContain(expectedCode);
  }

  async assertRoomReconnected(expectedQueueLength: number): Promise<void> {
    await expect.poll(async () => {
      const response = await fetch(`${this.platformBaseUrl}/api/rooms`);
      if (!response.ok) return null;
      const rooms = await response.json() as Array<{ ip?: string; online?: boolean; queueLength?: number }>;
      return {
        count: rooms.length,
        ip: rooms[0]?.ip,
        online: rooms[0]?.online,
        queueLength: rooms[0]?.queueLength,
      };
    }, { timeout: 30_000 }).toEqual({ count: 1, ip: "127.0.0.1", online: true, queueLength: expectedQueueLength });
    expect(await this.waitingQueueLength()).toBe(expectedQueueLength);
  }

  async waitingQueueLength(): Promise<number> {
    const response = await fetch(`${this.gameBaseUrl}/engine/game/queue`);
    if (!response.ok) throw new Error(`Queue query failed with HTTP ${response.status}`);
    const body = await response.json() as { data?: { waiting?: unknown[] } };
    return body.data?.waiting?.length ?? 0;
  }

  async endCurrentGame(): Promise<void> {
    await expect(this.debugPage.getByTestId("game-debug-panel")).toHaveAttribute("data-runtime-mode", "SIMULATION");
    await this.debugPage.getByTestId("game-debug-end").click();
  }

  async completeCurrentGameNaturally(): Promise<void> {
    await expect(this.debugPage.getByTestId("game-debug-panel")).toHaveAttribute("data-runtime-mode", "SIMULATION");
    await this.debugPage.getByTestId("game-debug-complete-natural").click();
  }

  async completeCurrentGameThroughFloor(): Promise<void> {
    if (this.#options.runtimeMode !== "PRODUCTION" || !this.#floorDevice) {
      throw new Error("Production floor completion is only available in PRODUCTION acceptance mode");
    }
    expect(await this.currentRuntimeMode()).toBe("PRODUCTION");
    await this.#floorDevice.sendFloorTap(0, 0);
  }

  async settlementDiagnostics(): Promise<{ counts: { pending: number; delivered: number; failed: number }; deliveries: Array<{ platformPlayId: number; state: string; attemptCount: number; lastErrorCode?: string | null }> }> {
    const response = await fetch(`${this.gameBaseUrl}/api/member-platform/settlements`);
    if (!response.ok) throw new Error(`Settlement diagnostics failed with HTTP ${response.status}`);
    return response.json();
  }

  async currentWristbandUid(): Promise<string | null> {
    const access = this.touchPage.getByTestId("game-player-access");
    if (!await access.count()) return null;
    return access.getAttribute("data-wristband-uid");
  }

  async assertNaturalCrossClientState(phone: string, uid: string): Promise<void> {
    let info: { points: { total: number; rank: number }; recentPlays: Array<{ id: number; status: string; terminationReason: string; rawScore: number; pointsAwarded: number; scoringPolicy: string }>; wristbands: Array<{ uid: string; remainingSeconds: number }> } | null = null;
    await expect.poll(async () => {
      const response = await fetch(`${this.platformBaseUrl}/api/player-info?phone=${encodeURIComponent(phone)}`);
      if (!response.ok) return null;
      info = await response.json();
      return info.recentPlays?.[0]?.status;
    }, { timeout: 30_000 }).toBe("COMPLETED");
    if (!info) throw new Error("Player Info was not loaded");
    const settled = info.recentPlays[0];
    expect(settled).toMatchObject({ status: "COMPLETED", terminationReason: "NATURAL_COMPLETION", rawScore: 1, pointsAwarded: 1, scoringPolicy: "raw-score-v1" });
    expect(info.points).toEqual({ total: 1, rank: 1 });
    expect(info.wristbands.find((band) => band.uid === uid)?.remainingSeconds).toBeGreaterThan(0);

    const admin = this.adminPage;
    await admin.getByTestId("admin-nav-members").click();
    const memberRow = admin.locator('tr[data-testid^="admin-member-"]').filter({ hasText: phone });
    await expect(memberRow).toBeVisible();
    await expect(memberRow.getByTestId("admin-member-points")).toHaveText("1");
    await expect(memberRow.getByTestId("admin-member-rank")).toHaveText("#1");
    await admin.getByTestId("admin-nav-rooms").click();
    const room = admin.locator('[data-testid^="admin-room-"]').first();
    await expect(room).toBeVisible();
    await expect(room.getByTestId("admin-room-connection")).toHaveAttribute("data-online", "true");
    await admin.getByTestId("admin-nav-records").click();
    await admin.getByTestId("admin-record-tab-plays").click();
    const playRecord = admin.locator('tr[data-testid^="admin-play-"]').filter({ hasText: uid }).first();
    await expect(playRecord).toBeVisible();
    await expect(playRecord).toHaveAttribute("data-status", "COMPLETED");
    await expect(playRecord.getByTestId("admin-play-raw-score")).toHaveText("1");
    await expect(playRecord.getByTestId("admin-play-points")).toContainText("1");
    await expect(playRecord.getByTestId("admin-play-points")).toContainText("raw-score-v1");
    await expect(playRecord.getByTestId("admin-play-termination")).toContainText("NATURAL_COMPLETION");

    const kiosk = this.kioskPage;
    await kiosk.goto(`http://127.0.0.1:${this.#ports.kiosk}/`, { waitUntil: "domcontentloaded" });
    await kiosk.getByTestId("kiosk-player-info").click();
    await kiosk.getByTestId("kiosk-info-phone").fill(phone);
    await kiosk.getByTestId("kiosk-info-submit").click();
    await expect(kiosk.getByTestId("kiosk-info-result")).toBeVisible();
    await expect(kiosk.getByTestId(`kiosk-info-wristband-${uid}`)).toBeVisible();
    await expect(kiosk.getByTestId("kiosk-info-points-total")).toHaveText("1");
    await expect(kiosk.getByTestId("kiosk-info-rank")).toHaveText("#1");
    const kioskPlay = kiosk.locator('article[data-testid^="kiosk-info-play-"]').first();
    await expect(kioskPlay).toHaveAttribute("data-status", "COMPLETED");
    await expect(kioskPlay.getByTestId("kiosk-info-play-raw-score")).toHaveText("1");
    await expect(kioskPlay.getByTestId("kiosk-info-play-points")).toHaveText("+1");
  }

  async assertManualAbortState(phone: string): Promise<void> {
    await expect.poll(async () => {
      const response = await fetch(`${this.platformBaseUrl}/api/player-info?phone=${encodeURIComponent(phone)}`);
      if (!response.ok) return null;
      const info = await response.json() as { points: { total: number }; recentPlays: Array<{ status: string; pointsAwarded: number; terminationReason: string }> };
      const play = info.recentPlays[0];
      return { total: info.points.total, play: play ? {
        status: play.status,
        pointsAwarded: play.pointsAwarded,
        terminationReason: play.terminationReason,
      } : null };
    }, { timeout: 30_000 }).toEqual({ total: 0, play: { status: "ABORTED", pointsAwarded: 0, terminationReason: "MANUAL_STOP" } });
  }

  async assertConflictingDuplicateSettlementIsIgnored(phone: string): Promise<void> {
    const beforeResponse = await fetch(`${this.platformBaseUrl}/api/player-info?phone=${encodeURIComponent(phone)}`);
    if (!beforeResponse.ok) throw new Error(`Player Info query failed with HTTP ${beforeResponse.status}`);
    const before = await beforeResponse.json() as { points: { total: number }; recentPlays: Array<{ id: number; rawScore: number; pointsAwarded: number; terminationReason: string }> };
    const play = before.recentPlays[0];
    const duplicate = await fetch(`${this.platformBaseUrl}/api/game-plays/${play.id}/result`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ success: false, terminationReason: "NATURAL_FAILURE", rawScore: 999999, pointsAwarded: 999999 }),
    });
    expect(duplicate.ok).toBe(true);
    expect(await duplicate.json()).toMatchObject({
      terminationReason: play.terminationReason,
      rawScore: play.rawScore,
      pointsAwarded: play.pointsAwarded,
    });
    const afterResponse = await fetch(`${this.platformBaseUrl}/api/player-info?phone=${encodeURIComponent(phone)}`);
    const after = await afterResponse.json() as { points: { total: number }; recentPlays: unknown[] };
    expect(after.points.total).toBe(before.points.total);
    expect(after.recentPlays).toHaveLength(before.recentPlays.length);
  }

  async #attachDiagnostics(prefix: string): Promise<void> {
    const summary = {
      runDirectory: this.#runDirectory,
      ports: this.#ports,
      runtimeMode: this.#options.runtimeMode,
      platformClients: this.#options.platformClients,
      floorDevice: this.#floorDevice?.diagnostics() ?? null,
      processes: this.#children.map((process) => ({
        label: process.label,
        pid: process.child.pid,
        exitCode: process.child.exitCode,
        signalCode: process.child.signalCode,
      })),
    };
    await this.#testInfo.attach(`${prefix}-运行配置`, { body: Buffer.from(JSON.stringify(summary, null, 2)), contentType: "application/json" });
    for (const process of this.#children) {
      await this.#testInfo.attach(`${prefix}-${processNameInChinese(process.label)}-日志末尾`, { body: Buffer.from(process.log.text()), contentType: "text/plain" });
    }
  }

  async stop(passed: boolean): Promise<void> {
    if (this.#stopped) return;
    this.#stopped = true;
    if (!passed) await this.#attachDiagnostics("测试失败");
    const failures: unknown[] = [];
    try { await this.#electronApp?.close(); } catch (error) { failures.push(error); }
    try { await this.#registrationElectron?.close(); } catch (error) { failures.push(error); }
    try { await this.#memberAdminElectron?.close(); } catch (error) { failures.push(error); }
    try { await this.#browser?.close(); } catch (error) { failures.push(error); }
    try { await this.#processes.stopAll(); } catch (error) { failures.push(error); }
    if (process.env.ACCEPTANCE_KEEP_RUNTIME === "1") {
      await this.#testInfo.attach("保留的临时运行目录", { body: Buffer.from(this.#runDirectory), contentType: "text/plain" });
    } else {
      try { await removeOwnedRunDirectory(this.#runDirectory, runtimeBase); } catch (error) { failures.push(error); }
    }
    if (failures.length && passed) throw new AggregateError(failures, "Acceptance cleanup failed");
  }
}
