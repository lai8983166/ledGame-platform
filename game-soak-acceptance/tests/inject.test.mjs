import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { injectFromRun } from '../src/inject.mjs';

test('injection is explicit, session bound DOWN/UP only and never calls debug/score/start', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'soak-input-'));
  try {
    await fs.writeFile(path.join(directory, 'isolation.json'), JSON.stringify({ backendPort: 1234, runId: 'test' }));
    await fs.writeFile(path.join(directory, 'config.json'), JSON.stringify({ simulatedInputEnabled: true }));
    const calls = [];
    const fetcher = async (url, options) => {
      calls.push({ url, options });
      return { ok: true, json: async () => ({ code: 200, data: url.endsWith('/state')
        ? { engineState: 'RUNNING', sessionId: 'session' } : { sessionId: 'session', lastSequence: 5 } }) };
    };
    await injectFromRun(directory, 'DOWN', 0, 0, fetcher);
    assert.deepEqual(JSON.parse(calls[2].options.body), { runId: 'test', sessionId: 'session', sequence: 6, action: 'DOWN', x: 0, y: 0 });
    assert.equal(calls.length, 3);
    assert(calls.every(({ url }) => url.startsWith('http://127.0.0.1:1234/')));
    await assert.rejects(() => injectFromRun(directory, 'EndGame', 0, 0, fetcher), /DOWN/);
    await fs.writeFile(path.join(directory, 'config.json'), '{}');
    await assert.rejects(() => injectFromRun(directory, 'DOWN', 0, 0, fetcher), /没有启用/);
    assert.equal(calls.length, 3);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
