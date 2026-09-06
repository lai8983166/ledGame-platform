import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import { runAcceptance } from '../src/run.mjs';

const config = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
if (config.hardwareMode !== 'simulated' || config.durationHours > 0.1) throw new Error('本开发入口仅支持明确 simulated 且不超过 6 分钟');
const { result } = await runAcceptance(config);
assert.equal(result.status, '完成', result.error);
assert.equal(result.monitoring.communication.status, '未验证');
console.log(JSON.stringify(result, null, 2));
