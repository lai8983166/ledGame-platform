import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runLoop, validateTerminal } from '../src/loop.mjs';

const game = { gameId: 41, playerCount: 2, startLevelIndex: 0, durationSeconds: 3600 };
const finalState = { engineState: 'STOPPED', gameId: 41, sessionId: 'session-a', runtimeMode: 'PRODUCTION',
  userCount: 2, startLevelIndex: 0, terminationReason: 'NATURAL_FAILURE', queueSummary: { waiting: [], current: null } };

test('natural loss is normal, manual stop and wrong sessions are not', () => {
  assert.doesNotThrow(() => validateTerminal(finalState, game, 'session-a'));
  for (const diff of [{ terminationReason: 'MANUAL_STOP' }, { sessionId: 'old' }, { gameId: 42 },
    { runtimeMode: 'SIMULATION' }, { queueSummary: { waiting: [{ id: 1 }] } }, { sessionId: null }]) {
    assert.throws(() => validateTerminal({ ...finalState, ...diff }, game, 'session-a'));
  }
});

test('virtual eighteen hours loops ordered IDs, starts no extra game at deadline', async () => {
  let time = 1000; const selected = []; const records = [];
  const result = await runLoop({ games: [game, { ...game, gameId: 42 }], targetMillis: 18 * 3600000 }, {
    now: () => time,
    checkContent: async () => {},
    play: async (target, onRunning) => {
      selected.push(target.gameId); onRunning(); time += 3600000;
      return { runningMillis: 3600000, sessionId: `session-${selected.length}` };
    },
    writeRound: async (r) => records.push(r),
  });
  assert.equal(selected.length, 18);
  assert.deepEqual(selected.slice(0, 4), [41, 42, 41, 42]);
  assert.equal(result.status, '完成');
  assert.equal(result.runningMillis, 18 * 3600000);
  assert.equal(records.length, 18);
});

test('deadline includes normal between-game operations, final round drains naturally', async () => {
  let time = 0; let rounds = 0;
  const result = await runLoop({ games: [game], targetMillis: 100 }, {
    now: () => time,
    checkContent: async () => { time += 20; },
    play: async (_, onRunning) => { onRunning(); time += 60; rounds++; return { runningMillis: 60 }; },
    writeRound: async () => {},
  });
  assert.equal(rounds, 2);
  assert.equal(result.elapsedMillis, 140);
  assert.equal(result.drainMillis, 40);
});

test('content check reaching deadline does not start another game', async () => {
  let time = 0; let rounds = 0;
  const result = await runLoop({ games: [game], targetMillis: 100 }, {
    now: () => time,
    checkContent: async () => { time += 101; },
    play: async (_, onRunning) => { onRunning(); time += 10; rounds++; return { runningMillis: 10 }; },
    writeRound: async () => {},
  });
  assert.equal(rounds, 1);
  assert.equal(result.status, '完成');
});

test('crash fails immediately and never retries or restarts', async () => {
  let attempts = 0;
  const result = await runLoop({ games: [game], targetMillis: 100 }, {
    checkContent: async () => {},
    play: async () => { attempts++; throw new Error('Java 退出'); },
    writeRound: async () => {},
  });
  assert.equal(attempts, 1);
  assert.equal(result.status, '失败');
  assert.match(result.error, /Java/);
});

test('abort is unfinished and lack of full target coverage cannot pass', async () => {
  const controller = new AbortController(); controller.abort();
  const result = await runLoop({ games: [game], targetMillis: 100 }, { signal: controller.signal });
  assert.equal(result.status, '未完成');
  let time = 0;
  const partial = await runLoop({ games: [game, { ...game, gameId: 42 }], targetMillis: 100 }, {
    now: () => time, checkContent: async () => {},
    play: async (_, start) => { start(); time += 200; return { runningMillis: 200 }; },
    writeRound: async () => {},
  });
  assert.equal(partial.status, '失败');
  assert.match(partial.error, /覆盖/);
});
