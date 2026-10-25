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

function startHealthServer(port, state) {
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    const reply = (body, status=200) => { response.writeHead(status, { 'content-type':'application/json' }); response.end(JSON.stringify(body)); };
    if (request.url === "/api/health") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"status":"ok"}');
      return;
    }
    if (url.pathname === '/api/members' && request.method === 'GET') {
      reply(state.members.filter(member => member.phone === url.searchParams.get('phone'))); return;
    }
    if (url.pathname === '/api/members' && request.method === 'POST') {
      let body=''; for await (const chunk of request) body += chunk;
      const member = {...JSON.parse(body), id:2, createdAt:'2026-10-25', status:'ACTIVE'};
      state.members.push(member); state.registrations++; reply(member); return;
    }
    if (url.pathname.startsWith('/api/wristbands/') && request.method === 'GET') {
      const finish = () => reply({uid:url.pathname.split('/').at(-1),status:'CHARGED',durationMinutes:60});
      if(state.holdRead) state.pendingReads.push(finish); else finish();
      return;
    }
    if (url.pathname === '/api/wristbands/bind') {
      let body=''; for await (const chunk of request) body += chunk;
      const binding = JSON.parse(body); state.bindings++;
      const duplicate = state.active.has(binding.uid);
      state.active.add(binding.uid);
      const finish = () => reply(duplicate ? {code:'WRISTBAND_ALREADY_BOUND',message:'此手环已绑定'} : {durationMinutes:60}, duplicate ? 409 : 200);
      if(state.holdBind) state.pendingBinds.push(finish); else finish();
      return;
    }
    if (url.pathname === '/api/player-info') {
      state.queries++;
      const finish = () => reply({ profile:state.members[0], points:{total:100,remaining:100,ranking:100,rank:1}, wristbands:[], recentPlays:[] });
      if(state.holdQuery) state.pendingQueries.push(finish); else finish();
      return;
    }
    reply({code:'NOT_FOUND'},404);
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

test("自助端：一步启动、自动刷卡、取消超时、迟到响应及断线重连", async () => {
  test.setTimeout(180000);
  const rendererPort = await freePort();
  const apiPort = await freePort();
  const unavailablePort = await freePort();
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-kiosk-electron-"));
  const childEnv = { ...process.env };
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const packaged = process.env.LEDGAME_TEST_PACKAGED_KIOSK === '1';
  const vite = packaged ? null : spawn(process.execPath, [path.join(root, "apps/registration-kiosk/node_modules/vite/bin/vite.js"), "--host", "127.0.0.1", "--port", String(rendererPort)], {
    cwd: path.join(root, "apps/registration-kiosk"), env: childEnv, windowsHide: true, stdio: "ignore",
  });
  const state = {members:[{id:1,name:'刷卡测试',phone:'13800138880',avatarId:'nova',createdAt:'2026-10-25',status:'ACTIVE'}],
    bindings:0,registrations:0,queries:0,active:new Set(),holdRead:false,holdBind:false,holdQuery:false,pendingReads:[],pendingBinds:[],pendingQueries:[]};
  let api = await startHealthServer(apiPort,state);
  let desktop;
  const startDesktop = () => electron.launch({
    ...(packaged ? {executablePath:path.join(root,'release/registration-kiosk/win-unpacked/LED Game 自助注册端.exe')} : {args:[path.join(root,'desktop/registration-kiosk/main.cjs')]}),
    env:{...childEnv, ...(!packaged ? {VITE_REGISTRATION_KIOSK_DEV_URL:`http://127.0.0.1:${rendererPort}`} : {}), LEDGAME_USER_DATA:userData},
  });
  try {
    if (!packaged) await waitForUrl(`http://127.0.0.1:${rendererPort}`);
    desktop = await startDesktop();
    const operator = await desktop.firstWindow();
    await expect(operator.getByTestId("operator-host")).toBeVisible();
    expect(desktop.windows()).toHaveLength(1);

    await operator.getByTestId("operator-host").fill("127.0.0.1");
    await operator.getByTestId("operator-port").fill(String(unavailablePort));
    await expect(operator.getByTestId('operator-save')).toHaveCount(0);
    await expect(operator.getByTestId('operator-test')).toHaveCount(0);
    await operator.getByTestId("operator-launch").click();
    await expect(operator.getByTestId("operator-error-dialog")).toContainText(/网络|network/i);
    expect(desktop.windows()).toHaveLength(1);
    await operator.screenshot({path:test.info().outputPath('启动连接失败-可关闭提示.png')});
    await operator.getByTestId('operator-error-close').click();
    await expect(operator.getByTestId("operator-launch")).toBeEnabled();

    await operator.getByTestId("operator-port").fill(String(apiPort));
    await operator.getByTestId("operator-launch").click();

    await expect.poll(() => desktop.windows().length).toBe(2);
    const kiosk = desktop.windows().find((page) => page !== operator);
    await expect(kiosk.getByTestId("kiosk-screen-home")).toBeVisible();
    await kiosk.evaluate(() => localStorage.setItem('ledgame.registration-kiosk.flow-timeouts',JSON.stringify({activation:60,wristband:10,success:30,playerInfo:10})));
    await kiosk.reload();
    await expect(kiosk.getByTestId('kiosk-offline-overlay')).toBeHidden({timeout:10000});
    await expect.poll(() => kiosk.evaluate(() => ({
      readSettings: typeof window.registrationDesktop?.readSettings,
      saveSettings: typeof window.registrationDesktop?.saveSettings,
      startKiosk: typeof window.registrationDesktop?.startKiosk,
    }))).toEqual({ readSettings: "undefined", saveSettings: "undefined", startKiosk: "undefined" });

    await desktop.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().find((win) => win.isKiosk())?.close());
    await expect.poll(() => desktop.windows().length).toBe(2);

    const openActivation = async (phone='13800138880') => {
      await kiosk.getByTestId('kiosk-activate').click();
      await kiosk.getByTestId('kiosk-member-phone').fill(phone);
      await kiosk.getByTestId('kiosk-member-submit').click();
      await kiosk.getByTestId('kiosk-existing-continue').click();
      await expect(kiosk.getByTestId('kiosk-scan-dialog')).toBeVisible();
      await expect(kiosk.getByTestId('soft-keyboard')).toHaveCount(0);
    };
    const scan = async uid => { await kiosk.keyboard.type(uid); await kiosk.keyboard.press('Enter'); };
    const home = async () => kiosk.getByRole('button',{name:'Return Home'}).click();
    await openActivation();
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    await expect(kiosk.getByTestId('kiosk-screen-confirm')).toBeVisible();
    expect(state.bindings).toBe(0);
    await kiosk.getByTestId('kiosk-existing-continue').click();
    state.holdRead=true;
    await scan('2283055618'); await scan('2283055618');
    await expect.poll(()=>state.pendingReads.length).toBe(1);
    state.holdRead=false; state.pendingReads.splice(0).forEach(reply=>reply());
    await expect(kiosk.getByTestId('kiosk-bind-success')).toBeVisible();
    expect(state.bindings).toBe(1);
    await home();

    await openActivation();
    await scan('2283055618');
    await expect(kiosk.getByTestId('kiosk-bind-error')).toContainText('此手环已绑定');
    expect(state.bindings).toBe(2);
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    await kiosk.getByRole('button',{name:'Not me'}).click();
    await kiosk.getByTestId('kiosk-screen-phone').getByRole('button',{name:'Back',exact:true}).click();

    await kiosk.getByTestId('kiosk-player-info').click();
    await kiosk.getByTestId('kiosk-info-phone').fill('13800138880');
    await kiosk.getByTestId('kiosk-info-mode-wristband').click();
    await expect(kiosk.getByTestId('kiosk-scan-dialog')).toBeVisible();
    await expect(kiosk.getByTestId('soft-keyboard')).toHaveCount(0);
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    await expect(kiosk.getByTestId('kiosk-info-phone')).toBeVisible();
    expect(state.queries).toBe(0);
    await kiosk.getByTestId('kiosk-info-mode-wristband').click();
    state.holdQuery=true; await scan('2283055618');
    await expect.poll(()=>state.pendingQueries.length).toBe(1);
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    state.holdQuery=false; state.pendingQueries.splice(0).forEach(reply=>reply());
    await kiosk.waitForTimeout(200);
    await expect(kiosk.getByTestId('kiosk-info-result')).toHaveCount(0);
    await kiosk.getByTestId('kiosk-info-mode-wristband').click();
    await scan('2283055618');
    await expect(kiosk.getByTestId('kiosk-info-result')).toBeVisible();
    expect(state.bindings).toBe(2);
    await kiosk.screenshot({path:test.info().outputPath('手环直接查询-只读结果.png')});
    await home();

    await kiosk.getByTestId('kiosk-activate').click();
    await kiosk.getByTestId('kiosk-member-phone').fill('13800138881');
    await kiosk.getByTestId('kiosk-member-submit').click();
    await kiosk.getByTestId('kiosk-registration-name').fill('新客自动刷卡');
    await kiosk.getByTestId('kiosk-registration-birth-year').fill('1990');
    await kiosk.getByTestId('kiosk-registration-birth-month').fill('01');
    await kiosk.getByTestId('kiosk-registration-birth-day').fill('02');
    await kiosk.getByTestId('kiosk-registration-gender-female').click();
    await kiosk.getByTestId('kiosk-registration-submit').click();
    await expect(kiosk.getByTestId('kiosk-scan-dialog')).toBeVisible();
    expect(state.registrations).toBe(1);
    await kiosk.screenshot({path:test.info().outputPath('注册后直接等待刷卡.png')});
    await expect(kiosk.getByTestId('kiosk-screen-home')).toBeVisible({timeout:13000});
    expect(state.bindings).toBe(2);

    await openActivation();
    state.holdRead=true; await scan('2283055619');
    await expect.poll(()=>state.pendingReads.length).toBe(1);
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    state.holdRead=false; state.pendingReads.splice(0).forEach(reply=>reply());
    await kiosk.waitForTimeout(200);
    expect(state.bindings).toBe(2);
    await kiosk.getByTestId('kiosk-existing-continue').click();
    state.holdBind=true; await scan('2283055619');
    await expect.poll(()=>state.pendingBinds.length).toBe(1);
    await kiosk.getByTestId('kiosk-scan-cancel').click();
    state.holdBind=false; state.pendingBinds.splice(0).forEach(reply=>reply());
    await kiosk.waitForTimeout(700);
    await expect(kiosk.getByTestId('kiosk-screen-confirm')).toBeVisible();
    await expect(kiosk.getByTestId('kiosk-bind-success')).toHaveCount(0);
    await kiosk.getByTestId('kiosk-existing-continue').click();

    await new Promise((resolve) => api.close(resolve));
    await expect(kiosk.getByTestId("kiosk-offline-overlay")).toBeVisible({ timeout: 10000 });
    api = await startHealthServer(apiPort,state);
    await expect(kiosk.getByTestId("kiosk-offline-overlay")).toBeHidden({ timeout: 10000 });
    await expect(kiosk.getByTestId('kiosk-screen-home')).toBeVisible();

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
    await expect(operator.getByTestId('operator-launch')).toBeEnabled();
    await operator.getByTestId('operator-launch').click();
    await expect.poll(()=>desktop.windows().length).toBe(2);
    const second = desktop.windows().find(page=>page!==operator);
    await expect(second.getByTestId('kiosk-screen-home')).toBeVisible();
    await second.getByTestId('kiosk-staff-exit-hotspot').dblclick();
    await second.getByTestId('kiosk-exit-password').fill('888888');
    await second.getByTestId('kiosk-exit-submit').click();
    await expect.poll(()=>desktop.windows().length).toBe(1);
    await desktop.close(); desktop = await startDesktop();
    const restored = await desktop.firstWindow();
    await expect(restored.getByTestId('operator-host')).toHaveValue('127.0.0.1');
    await expect(restored.getByTestId('operator-port')).toHaveValue(String(apiPort));
    await expect(restored.getByTestId('operator-launch')).toBeEnabled();
  } finally {
    if (desktop) {
      await desktop.evaluate(({BrowserWindow})=>{for(const window of BrowserWindow.getAllWindows()) window.destroy();}).catch(()=>{});
      await desktop.close().catch(() => {});
    }
    vite?.kill();
    await new Promise((resolve) => api.close(resolve));
    await fs.rm(userData, { recursive: true, force: true });
  }
});
