import { performance } from 'node:perf_hooks';

export function validateTerminal(state, target, sessionId) {
  if (!sessionId || state.sessionId !== sessionId) throw new Error('结束会话缺失或不匹配');
  if (state.engineState !== 'STOPPED' || !['NATURAL_SUCCESS', 'NATURAL_FAILURE'].includes(state.terminationReason)) {
    throw new Error(`不是自然结束：${state.engineState}/${state.terminationReason}`);
  }
  if (String(state.gameId) !== String(target.gameId) || state.userCount !== target.playerCount || state.startLevelIndex !== target.startLevelIndex) throw new Error('实际游戏/人数/关卡与计划不一致');
  if (state.runtimeMode !== 'PRODUCTION') throw new Error('实际运行不是 PRODUCTION');
  if (!state.queueSummary || !Array.isArray(state.queueSummary.waiting)) throw new Error('排队证据缺失');
  if (state.queueSummary.waiting.length || state.queueSummary.current) throw new Error('出现意外排队');
}

// Keeps only aggregate counters; round details are streamed by writeRound.
// play must enforce its own finite game/settlement timeout; no stop/restart recovery exists here.
export async function runLoop(plan, {
  now = () => performance.now(), signal, checkContent, play, writeRound,
} = {}) {
  let started = null; let runningMillis = 0; let rounds = 0;
  const counts = Object.fromEntries(plan.games.map((g) => [g.gameId, 0]));
  let status = '完成'; let error;
  try {
    while (started === null || now() - started < plan.targetMillis) {
      signal?.throwIfAborted();
      await checkContent();
      signal?.throwIfAborted();
      if (started !== null && now() - started >= plan.targetMillis) break;
      const target = plan.games[rounds % plan.games.length];
      let observedRunning = false;
      const round = await play(target, () => {
        if (observedRunning) throw new Error('一局重复报告首次 RUNNING');
        observedRunning = true;
        if (started === null) started = now();
      });
      if (!observedRunning || !Number.isFinite(round.runningMillis) || round.runningMillis < 0) throw new Error('缺少 RUNNING 证据');
      await writeRound({ ...round, gameId: target.gameId, ordinal: rounds + 1 });
      runningMillis += round.runningMillis;
      counts[target.gameId]++;
      rounds++;
    }
    signal?.throwIfAborted();
    if (Object.values(counts).some((n) => n === 0)) throw new Error('目标游戏未全部覆盖');
  } catch (cause) {
    status = signal?.aborted ? '未完成' : '失败';
    error = String(cause?.message ?? cause);
  }
  const elapsedMillis = started === null ? 0 : now() - started;
  return { status, error, rounds, counts, elapsedMillis, runningMillis,
    targetMillis: plan.targetMillis, drainMillis: Math.max(0, elapsedMillis - plan.targetMillis) };
}
