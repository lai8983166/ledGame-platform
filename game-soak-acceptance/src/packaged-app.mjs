import { _electron } from 'playwright';
import { FloorDevice } from './floor-device.mjs';
import { prepareIsolatedRuntime } from './isolated-runtime.mjs';

export async function launchPackagedGame(config, preparedRuntime) {
  const runtime = preparedRuntime ?? await prepareIsolatedRuntime(config);
  const floor = config.hardwareMode === 'simulated' ? new FloorDevice(runtime.width, runtime.height) : null;
  let electron; let closing = false; let failure;
  const checkAlive = () => { if (failure) throw new Error(failure); };
  const get = async (endpoint) => {
    checkAlive();
    const response = await fetch(`http://127.0.0.1:${runtime.backendPort}${endpoint}`, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error(`游戏后端请求失败 ${response.status}：${endpoint}`);
    const result = await response.json();
    if (result.code !== 200) throw new Error(`游戏后端错误：${result.message}`);
    if (endpoint === '/engine/game/state') floor?.observeState(result.data);
    return result.data;
  };
  const close = async () => {
    closing = true;
    try { await electron?.close(); }
    finally { await floor?.close(); await runtime.verifySources(); }
  };
  try {
    await floor?.start(runtime.floorPort);
    electron = await _electron.launch({ executablePath: runtime.executable, env: runtime.env, timeout: (config.limits?.startupSeconds ?? 60) * 1000 });
    electron.process().once('exit', (code, signal) => { if (!closing) failure = `游戏端退出：code=${code} signal=${signal}`; });
    const deadline = Date.now() + (config.limits?.startupSeconds ?? 60) * 1000;
    let ready = false;
    while (Date.now() < deadline) {
      checkAlive();
      try { await get('/engine/game/state'); ready = true; break; } catch { /* startup only */ }
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    if (!ready) throw new Error('自有后端启动超时，请检查本轮 backend.log');
    return { runtime, electron, floor, get, checkAlive, close };
  } catch (error) { await close(); throw error; }
}

export async function openTouchUi(app) {
  let main;
  for (const page of app.electron.windows()) {
    if (await page.getByTestId('game-enter-flow').count()) { main = page; break; }
  }
  if (!main) {
    main = await app.electron.firstWindow();
    await main.getByTestId('game-enter-flow').waitFor({ timeout: 15000 });
  }
  await main.getByTestId('game-enter-flow').click({ timeout: 10000 });
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    app.checkAlive();
    for (const page of app.electron.windows()) {
      if (await page.getByTestId('game-touch-idle').isVisible()) {
        page.setDefaultTimeout(10000);
        return page;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('游戏模式待机界面未出现');
}
