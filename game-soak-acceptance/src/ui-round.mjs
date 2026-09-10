import { performance } from 'node:perf_hooks';
import { validateTerminal } from './loop.mjs';

// All business mutations are clicks. readState must be a read-only backend getter.
export async function playUiRound(page, target, {
  readState, onRunning, signal, gameCount, actionMillis = 10000, settleMillis = 30000,
  now = () => performance.now(), sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
}) {
  const touch = page.getByTestId('game-touch');
  const assertResponsive = async () => {
    signal?.throwIfAborted();
    if (page.isClosed()) throw new Error('Touch 窗口已关闭');
    const alert = page.getByTestId('game-error');
    if (await alert.isVisible()) throw new Error(`游戏界面错误：${(await alert.innerText()).slice(0, 1000)}`);
  };
  const until = async (check, millis, label) => {
    const deadline = now() + millis;
    do {
      await assertResponsive();
      if (await check()) return;
      await sleep(100);
    } while (now() < deadline);
    throw new Error(`${label} 超时；不强制结束、不自动重启`);
  };
  const click = async (id) => {
    await assertResponsive();
    await page.getByTestId(id).click({ timeout: actionMillis });
  };
  const step = async (expected) => until(async () => {
    const state = await touch.getAttribute('data-state', { timeout: actionMillis });
    if (state !== 'PREPARING' && state !== 'IDLE') throw new Error(`准备阶段异常状态：${state}`);
    return state === 'PREPARING' && await touch.getAttribute('data-preparation-step') === expected;
  }, actionMillis, `进入 ${expected}`);

  const initial = await readState();
  if (initial.engineState !== 'IDLE' || initial.queueSummary?.waiting?.length || initial.queueSummary?.current) throw new Error('开始前不是空闲或存在排队');
  await click('game-touch-idle');
  await step('players');
  const preparing = await readState();
  const sessionId = preparing.preparation?.sessionId;
  if (!sessionId) throw new Error('缺少准备会话证据');
  await click(`game-player-count-${target.playerCount}`);
  await click('game-player-next');
  await step('game');
  let selected = false;
  for (let index = 0; index < gameCount; index++) {
    if (await page.getByTestId('game-carousel').getAttribute('data-selected-game-id') === String(target.gameId)) { selected = true; break; }
    await click('game-carousel-next');
  }
  if (!selected) throw new Error(`轮播找不到目标游戏 ${target.gameId}`);
  await click('game-game-next');
  await step('level');
  await click(`game-level-${target.startLevelIndex}`);
  await click('game-start');
  let runningAt; let runningStartedAt;
  await until(async () => {
    const state = await readState();
    if (['IDLE', 'STOPPED'].includes(state.engineState)) throw new Error(`未进入 RUNNING 就变为 ${state.engineState}`);
    if (state.engineState !== 'RUNNING') return false;
    if (state.sessionId !== sessionId || String(state.gameId) !== String(target.gameId) || state.runtimeMode !== 'PRODUCTION') throw new Error('运行身份或模式不匹配');
    runningAt = now(); runningStartedAt = new Date().toISOString(); onRunning(); return true;
  }, 5000 + settleMillis, '开始游戏');
  let terminal;
  await until(async () => {
    const state = await readState();
    if (state.sessionId !== sessionId || String(state.gameId) !== String(target.gameId)) throw new Error('游玩中会话被替换');
    // STOPPING is the engine's normal transition after the game-over page and
    // before the terminal STOPPED snapshot. Keep polling within settleMillis;
    // validateTerminal still runs only on the final STOPPED state.
    if (!['RUNNING', 'SETTLING', 'STOPPING', 'STOPPED'].includes(state.engineState)) throw new Error(`游玩异常状态：${state.engineState}`);
    if (state.queueSummary?.waiting?.length || state.queueSummary?.current) throw new Error('游玩中出现意外排队');
    if (state.engineState !== 'STOPPED') return false;
    validateTerminal(state, target, sessionId); terminal = state; return true;
  }, target.durationSeconds * 1000 + settleMillis, '自然结束');
  if (!Number.isFinite(terminal.runningMillis)) throw new Error('缺少后端累计运行时间');
  await click('game-return-idle');
  await until(async () => (await readState()).engineState === 'IDLE', actionMillis, '返回待机');
  return { sessionId, runningStartedAt, returnedIdleAt: new Date().toISOString(), runningMillis: terminal.runningMillis, observedRoundMillis: now() - runningAt,
    terminationReason: terminal.terminationReason, terminal };
}
