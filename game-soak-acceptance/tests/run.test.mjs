import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { runAcceptance } from '../src/run.mjs';

const config = { hardwareMode: 'simulated', durationHours: 0.1, games: [{ gameId: 1, playerCount: 1, startLevelIndex: 0 }] };
const catalog = [{ summary: { id: 1, type: 'rank', minPlayers: 1, maxPlayers: 1 }, document: { id: 1, levels: [{ durationSeconds: 30 }] } }];
for (const fault of ['none', 'launch', 'preflight', 'loop', 'cleanup', 'source', 'abort', 'sampling']) {
  test(`runner always reports and closes owned app: ${fault}`, async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'soak-runner-'));
    let closed = 0; const controller = new AbortController();
    const dependencies = {
      prepare: async () => ({ directory, backendPort: 1, verifySources: async () => { if (fault === 'source') throw new Error('source changed'); } }),
      launch: async () => {
        if (fault === 'launch') throw new Error('launch failed');
        return { checkAlive() {}, close: async () => { closed++; if (fault === 'cleanup') throw new Error('cleanup failed'); } };
      },
      catalog: async () => fault === 'preflight' ? [] : catalog,
      open: async () => ({ screenshot: async () => {} }),
      sample: async () => ({ check() {}, stop: async () => {}, summary: () => ({ failure: fault === 'sampling' ? 'probe failed' : undefined }) }),
      loop: async () => {
        if (fault === 'loop') throw new Error('loop failed');
        if (fault === 'abort') { controller.abort(new Error('用户中止')); throw controller.signal.reason; }
        return { status: '完成', rounds: 1, counts: { 1: 1 } };
      },
    };
    try {
      const { result } = await runAcceptance(config, { dependencies, signal: controller.signal, log() {} });
      assert.equal(result.status, fault === 'none' ? '完成' : fault === 'abort' ? '未完成' : '失败');
      assert.equal(closed, fault === 'launch' ? 0 : 1);
      assert.deepEqual(JSON.parse(await fs.readFile(path.join(directory, 'result.json'), 'utf8')), JSON.parse(JSON.stringify(result)));
      assert.match(await fs.readFile(path.join(directory, '测试报告.md'), 'utf8'), /游戏端烤机报告/);
    } finally { await fs.rm(directory, { recursive: true, force: true }); }
  });
}
