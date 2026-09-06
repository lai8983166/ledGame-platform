import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import { runAcceptance } from '../src/run.mjs';
import { playUiRound } from '../src/ui-round.mjs';
import { injectFromRun } from '../src/inject.mjs';
import { prepareIsolatedRuntime } from '../src/isolated-runtime.mjs';

const config = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
if (config.hardwareMode !== 'simulated' || config.durationHours > 0.1) throw new Error('仅限无硬件开发短测');
config.simulatedInputEnabled = true;
let directory; const injected = new Set();
const { result } = await runAcceptance(config, { dependencies: {
  prepare: async (input) => { const runtime = await prepareIsolatedRuntime(input); directory = runtime.directory; return runtime; },
  play: (page, target, options) => playUiRound(page, target, { ...options, readState: async () => {
    const state = await options.readState();
    if (state.engineState === 'RUNNING' && !injected.has(state.sessionId)) {
      injected.add(state.sessionId);
      await injectFromRun(directory, 'DOWN', 0, 0);
      await injectFromRun(directory, 'UP', 0, 0);
    }
    return state;
  } }),
} });
assert.equal(result.status, '完成', result.error);
assert.equal(result.monitoring.input.simulatedEvents, injected.size * 2);
assert.equal(result.monitoring.input.rejectedEvents, 0);
assert.equal(result.monitoring.communication.status, '未验证');
console.log(`注入验收：${injected.size} 局，${result.monitoring.input.simulatedEvents} 个模拟事件；未冒充真实硬件收包`);
