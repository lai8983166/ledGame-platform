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

type MemberFixture = { phone: string; name: string; uid: string };
type RuntimePorts = { platform: number; admin: number; kiosk: number; game: number; renderer: number; debugTcp: number };

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
  readonly #processes = new ManagedProcessRegistry();
  readonly #children: ManagedChildProcess[] = [];
  #platformProcess: ManagedChildProcess | null = null;
  #platformStartCount = 0;
  #browser: Browser | null = null;
  #adminPage: Page | null = null;
  #kioskPage: Page | null = null;
  #electronApp: ElectronApplication | null = null;
  #mainPage: Page | null = null;
  #touchPage: Page | null = null;
  #debugPage: Page | null = null;
  #stopped = false;

  private constructor(testInfo: TestInfo, runDirectory: string, ports: RuntimePorts) {
    this.#testInfo = testInfo;
    this.#runDirectory = runDirectory;
    this.#ports = ports;
  }

  static async start(testInfo: TestInfo): Promise<StoreAcceptanceHarness> {
    const runDirectory = await createOwnedRunDirectory(runtimeBase);
    const ports: RuntimePorts = {
      platform: await allocateLoopbackPort(),
      admin: await allocateLoopbackPort(),
      kiosk: await allocateLoopbackPort(),
      game: await allocateLoopbackPort(),
      renderer: await allocateLoopbackPort(),
      debugTcp: await allocateLoopbackPort(),
    };
    const harness = new StoreAcceptanceHarness(testInfo, runDirectory, ports);
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
    await writeFile(path.join(electronUserData, "settings", "application.json"), `${JSON.stringify({
      entryMethod: "wristband",
      mode: "debug",
      memberPlatformHost: "127.0.0.1",
      memberPlatformPort: this.#ports.platform,
      secondaryDisplay: null,
    }, null, 2)}\n`, "utf8");
    await writeFile(path.join(this.#runDirectory, "configuration.json"), `${JSON.stringify({
      schemaVersion: 1,
      repositories: { platformRoot, gameRoot, gameBackendRoot },
      ports: this.#ports,
      browserChannel: process.env.ACCEPTANCE_BROWSER_CHANNEL || "msedge",
      headed: process.env.ACCEPTANCE_HEADED === "1",
    }, null, 2)}\n`, "utf8");

    await this.#startPlatform();
    this.#startChild("member-admin", "pnpm", ["--dir", path.join(platformRoot, "apps", "member-admin"), "exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.admin), "--strictPort"], platformRoot, {
      VITE_PLATFORM_BASE_URL: this.platformBaseUrl,
    });
    await this.#ready("Member Admin", `http://127.0.0.1:${this.#ports.admin}/`, this.#children.at(-1)!);

    this.#startChild("registration-kiosk", "pnpm", ["--dir", path.join(platformRoot, "apps", "registration-kiosk"), "exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.kiosk), "--strictPort"], platformRoot, {
      VITE_PLATFORM_BASE_URL: this.platformBaseUrl,
    });
    await this.#ready("Registration Kiosk", `http://127.0.0.1:${this.#ports.kiosk}/`, this.#children.at(-1)!);

    const runId = safeRunId(this.#runDirectory).replaceAll("-", "_");
    this.#startChild("game-backend", "mvn", ["-q", "spring-boot:run"], gameBackendRoot, {
      SPRING_PROFILES_ACTIVE: "acceptance",
      ACCEPTANCE_GAME_DATABASE_URL: `jdbc:h2:mem:${runId};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`,
      ACCEPTANCE_GAME_PORT: String(this.#ports.game),
      MEMBER_PLATFORM_BASE_URL: this.platformBaseUrl,
      LEDGAME_DEVICE_ID: `acceptance-${runId}`,
      LEDGAME_ROOM_ID: `acceptance-${runId}`,
      LEDGAME_ROOM_NAME: "Acceptance Room",
      LED_ROOM_RECONNECT_DELAY: "250ms",
    });
    await this.#ready("Game backend", `${this.gameBaseUrl}/engine/demo/state`, this.#children.at(-1)!, 120_000);
    const seed = await fetch(`${this.gameBaseUrl}/dev/seed/simple-variants`, { method: "POST" });
    if (!seed.ok) throw new Error(`Game seed failed with HTTP ${seed.status}: ${await seed.text()}`);

    this.#startChild("game-renderer", "pnpm", ["exec", "vite", "--host", "127.0.0.1", "--port", String(this.#ports.renderer), "--strictPort"], gameRoot);
    await this.#ready("Game renderer", `http://127.0.0.1:${this.#ports.renderer}/`, this.#children.at(-1)!);

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
    const game = this.touchPage.locator('[data-testid^="game-option-"]').first();
    await expect(game).toBeVisible();
    await game.click();
    await expect(this.touchPage.getByTestId("game-start")).toBeEnabled();
    await this.touchPage.getByTestId("game-start").click();
    await expect(this.touchPage.getByTestId("game-touch")).toHaveAttribute("data-state", "RUNNING", { timeout: 30_000 });
  }

  async #openGamePreparation(): Promise<void> {
    if (!this.#mainPage || !this.#electronApp) throw new Error("Electron is not started");
    if (!this.#touchPage || !this.#debugPage) {
      await this.#mainPage.getByTestId("game-enter-flow").click();
      await expect.poll(() => this.#electronApp!.windows().length, { timeout: 20_000 }).toBeGreaterThanOrEqual(3);
      const windows = this.#electronApp.windows();
      this.#touchPage = windows.find((page) => page.url().includes("window=touch")) ?? null;
      this.#debugPage = windows.find((page) => page.url().includes("window=debug")) ?? null;
      if (!this.#touchPage || !this.#debugPage) throw new Error(`Electron auxiliary windows missing: ${windows.map((page) => page.url()).join(", ")}`);
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
    if (!this.#platformProcess) throw new Error("Platform server is not started");
    await this.#platformProcess.stop();
    await expect.poll(async () => {
      try { return await httpOk(`${this.platformBaseUrl}/api/health`); } catch { return false; }
    }, { timeout: 15_000 }).toBe(false);
    await this.#startPlatform(clockOffsetSeconds);
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

  async currentWristbandUid(): Promise<string | null> {
    const access = this.touchPage.getByTestId("game-player-access");
    if (!await access.count()) return null;
    return access.getAttribute("data-wristband-uid");
  }

  async assertFinalCrossClientState(phone: string, uid: string): Promise<void> {
    const response = await fetch(`${this.platformBaseUrl}/api/player-info?phone=${encodeURIComponent(phone)}`);
    if (!response.ok) throw new Error(`Player Info query failed with HTTP ${response.status}`);
    const info = await response.json() as { recentPlays?: Array<{ status: string }>; wristbands?: Array<{ uid: string; remainingSeconds: number }> };
    expect(info.recentPlays?.some((play) => play.status === "ABORTED")).toBe(true);
    expect(info.wristbands?.find((band) => band.uid === uid)?.remainingSeconds).toBeGreaterThan(0);

    const admin = this.adminPage;
    await admin.getByTestId("admin-nav-members").click();
    await expect(admin.locator('tr[data-testid^="admin-member-"]').filter({ hasText: phone })).toBeVisible();
    await admin.getByTestId("admin-nav-rooms").click();
    const room = admin.locator('[data-testid^="admin-room-"]').first();
    await expect(room).toBeVisible();
    await expect(room.getByTestId("admin-room-connection")).toHaveAttribute("data-online", "true");
    await admin.getByTestId("admin-nav-records").click();
    await admin.getByTestId("admin-record-tab-plays").click();
    const playRecord = admin.locator('tr[data-testid^="admin-play-"]').filter({ hasText: uid }).first();
    await expect(playRecord).toBeVisible();
    await expect(playRecord).toHaveAttribute("data-status", "ABORTED");

    const kiosk = this.kioskPage;
    await kiosk.goto(`http://127.0.0.1:${this.#ports.kiosk}/`, { waitUntil: "domcontentloaded" });
    await kiosk.getByTestId("kiosk-player-info").click();
    await kiosk.getByTestId("kiosk-info-phone").fill(phone);
    await kiosk.getByTestId("kiosk-info-submit").click();
    await expect(kiosk.getByTestId("kiosk-info-result")).toBeVisible();
    await expect(kiosk.getByTestId(`kiosk-info-wristband-${uid}`)).toBeVisible();
    await expect(kiosk.locator('[data-testid^="kiosk-info-play-"]').first()).toHaveAttribute("data-status", "ABORTED");
  }

  async #attachDiagnostics(prefix: string): Promise<void> {
    const summary = {
      runDirectory: this.#runDirectory,
      ports: this.#ports,
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
