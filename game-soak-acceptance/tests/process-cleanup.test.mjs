import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sampleProcessTree, assertProcessesGone } from '../src/sampling.mjs';

test('Windows cleanup check detects a living owned identity but ignores PID reuse without killing anything', { skip: process.platform !== 'win32' }, async () => {
  const rows = await sampleProcessTree(process.pid);
  const main = rows.find((row) => row.pid === process.pid);
  assert(main?.createdAt);
  await assert.rejects(() => assertProcessesGone([{ pid: main.pid, createdAt: main.createdAt }]), /仍存在/);
  await assert.doesNotReject(() => assertProcessesGone([{ pid: main.pid, createdAt: '1900-01-01T00:00:00Z' }]));
  assert.equal(process.kill(process.pid, 0), true); // Signal zero probes only; never terminates.
});
