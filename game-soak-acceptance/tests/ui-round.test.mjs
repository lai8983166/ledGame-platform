import { test } from 'node:test';
import assert from 'node:assert/strict';
import { playUiRound } from '../src/ui-round.mjs';

function fixture(fault) {
  let step = 'players'; let time = 0; let read = 0; const clicks = [];
  const target = { gameId: 41, playerCount: 1, startLevelIndex: 0, durationSeconds: 1 };
  const queueSummary = { waiting: [], current: null };
  const stopped = { engineState: 'STOPPED', sessionId: 's1', gameId: 41, runtimeMode: 'PRODUCTION', userCount: 1,
    startLevelIndex: 0, terminationReason: 'NATURAL_SUCCESS', runningMillis: 1000, queueSummary };
  const states = [
    { engineState: 'IDLE', queueSummary },
    { engineState: 'PREPARING', preparation: { sessionId: 's1' } },
    { engineState: 'RUNNING', sessionId: 's1', gameId: 41, runtimeMode: 'PRODUCTION' },
    stopped,
    { engineState: 'IDLE' },
  ];
  if (fault === 'manual') states[3].terminationReason = 'MANUAL_STOP';
  if (fault === 'queue') states[3].queueSummary = { waiting: [{ id: 'q' }] };
  if (fault === 'no-running') states[2].engineState = 'STOPPED';
  if (fault === 'stopping') states.splice(3, 0, { ...stopped, engineState: 'STOPPING' });
  const page = {
    isClosed: () => false,
    getByTestId: (id) => ({
      isVisible: async () => false,
      getAttribute: async (attr) => {
        if (id === 'game-carousel') return fault === 'wrong-game' ? '42' : '41';
        if (attr === 'data-state') return fault === 'step-returned-idle' ? 'IDLE' : 'PREPARING';
        return step;
      },
      click: async () => {
        clicks.push(id);
        if (id === 'game-player-next') step = 'game';
        if (id === 'game-game-next') step = 'level';
        if (id === 'game-return-idle' && fault === 'end-page-stuck') throw new Error('结束页按钮无响应');
      },
    }),
  };
  return { clicks, execute: () => playUiRound(page, target, {
    gameCount: 2, onRunning: () => {}, now: () => time, sleep: async (ms) => { time += ms; },
    actionMillis: 1000, settleMillis: 1000,
    readState: async () => states[Math.min(read++, states.length - 1)],
  }) };
}

test('UI driver performs navigation contract without debug or direct start calls', async () => {
  const f = fixture(); assert.equal((await f.execute()).terminationReason, 'NATURAL_SUCCESS');
  assert.deepEqual(f.clicks, ['game-touch-idle', 'game-player-count-1', 'game-player-next', 'game-game-next', 'game-level-0', 'game-start', 'game-return-idle']);
});

test('UI driver waits through normal STOPPING transition before validating STOPPED', async () => {
  const f = fixture('stopping');
  assert.equal((await f.execute()).terminationReason, 'NATURAL_SUCCESS');
  assert.equal(f.clicks.at(-1), 'game-return-idle');
});

for (const fault of ['manual', 'queue', 'no-running', 'wrong-game', 'step-returned-idle', 'end-page-stuck']) {
  test(`UI deviation ${fault} fails rather than forcing game completion`, async () => {
    const f = fixture(fault); await assert.rejects(f.execute);
    assert.equal(f.clicks.some((id) => /debug|end-game|retry/.test(id)), false);
  });
}
