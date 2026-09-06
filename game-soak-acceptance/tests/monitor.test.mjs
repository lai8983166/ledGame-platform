import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryMonitor, ProcessMonitor, CommunicationMonitor } from '../src/monitor.mjs';
const processRow = (role, pid, mb = 100) => ({ role, pid, createdAt: '2026-09-06T00:00:00Z', responding: true, privateBytes: mb * 1048576, workingSetBytes: mb * 1048576 });

test('memory trends distinguish stable, growing, missing samples and absent thresholds', () => {
  for (const [growth, limits, gap, status] of [
    [0, true, false, '通过'], [100, true, false, '失败'], [0, false, false, '未判定'], [0, true, true, '未判定'],
  ]) {
    const monitor = new MemoryMonitor(limits ? { memoryLimitMB: 1000, memoryGrowthMBPerHour: 10 } : {});
    for (let at = 0; at <= 3000000; at += 10000) {
      if (gap && at === 1200000) continue;
      monitor.observe(at, [processRow('java', 1, 100 + growth * at / 3600000), processRow('main', 2)]);
    }
    assert.equal(monitor.summary().status, status);
  }
  const short = new MemoryMonitor({ memoryLimitMB: 1000, memoryGrowthMBPerHour: 10 });
  short.observe(0, [processRow('main', 1)]); assert.equal(short.summary().status, '未判定');
});

test('PID reuse and critical exit fail, utility exit and owned cleanup are allowed', () => {
  const rows = [processRow('main', 1), processRow('java', 2), processRow('renderer', 3), processRow('utility', 4)];
  const monitor = new ProcessMonitor(); monitor.observe(rows);
  monitor.observe(rows.slice(0, 3));
  assert.throws(() => monitor.observe(rows.slice(0, 2)), /退出/);
  assert.throws(() => monitor.observe(rows.map((r) => r.pid === 2 ? { ...r, createdAt: 'later' } : r)), /替换/);
  assert.throws(() => monitor.observe(rows.map((r) => r.pid === 3 ? { ...r, responding: false } : r)), /无响应/);
  assert.doesNotThrow(() => monitor.observe([], { cleaning: true }));
});

test('no real input, simulation, counter resets and missing data never turn into passes', () => {
  const base = { epoch: 'a', sendAttempts: 0, sendFailures: 0, receivedPackets: 0, receiveFailures: 0, parseRejected: 0, receiverStatusPackets: 0 };
  const monitor = new CommunicationMonitor(); monitor.observe(base); monitor.observe({ ...base, sendAttempts: 100 });
  assert.equal(monitor.summary('real').status, '未验证');
  monitor.observe({ ...base, sendAttempts: 200, receivedPackets: 1, receiverStatusPackets: 1 });
  assert.equal(monitor.summary('simulated').status, '未验证');
  assert.equal(monitor.summary('real').status, '通过');
  monitor.observe({ ...base, epoch: 'b' }); assert.equal(monitor.summary('real').status, '未验证');
  monitor.observe(null); assert.equal(monitor.summary('real').gaps, 1);
});
