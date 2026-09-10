import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MEMORY_PEAK_LIMIT_MB, MemoryMonitor, ProcessMonitor, CommunicationMonitor } from '../src/monitor.mjs';
const processRow = (role, pid, mb = 100) => ({ role, pid, createdAt: '2026-09-06T00:00:00Z', responding: true, privateBytes: mb * 1048576, workingSetBytes: mb * 1048576 });

test('memory uses a fixed 8 GiB peak limit and records growth without gating', () => {
  assert.equal(MEMORY_PEAK_LIMIT_MB, 8192);
  const monitor = new MemoryMonitor({ memoryLimitMB: 1, memoryGrowthMBPerHour: 0 });
  for (let at = 0; at <= 3000000; at += 10000) {
    monitor.observe(at, [processRow('java', 1, 100 + 100 * at / 3600000), processRow('main', 100)]);
  }
  const summary = monitor.summary();
  assert.equal(summary.status, '通过');
  assert.equal(summary.limits.memoryLimitMB, 8192);
  assert.equal(summary.limits.memoryGrowthMBPerHour, null);
  assert.ok(Number.isFinite(summary.growthMBPerHour));

  const over = new MemoryMonitor();
  over.observe(0, [processRow('main', 1, 8193)]);
  assert.equal(over.summary().status, '失败');

  const gap = new MemoryMonitor();
  gap.observe(0, [processRow('main', 1)]);
  gap.observe(30000, [processRow('main', 1)]);
  assert.equal(gap.summary().status, '未判定');
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

test('discovery evidence is aggregated and average excludes timeouts', () => {
  const base = { epoch: 'search', sendAttempts: 1, sendFailures: 0, receivedPackets: 1,
    receiveFailures: 0, parseRejected: 0, receiverStatusPackets: 1,
    searchAttempts: 0, searchResponseSamples: 0, searchTimeouts: 0, searchLatencyTotalMillis: 0 };
  const monitor = new CommunicationMonitor();
  monitor.observe(base);
  monitor.observe({ ...base, sendAttempts: 2, receivedPackets: 2, receiverStatusPackets: 2, searchAttempts: 1,
    searchResponseSamples: 2, searchTimeouts: 1, searchLatencyTotalMillis: 30 });
  const summary = monitor.summary('real');
  assert.deepEqual(summary.discovery, { attempts: 1, responseSamples: 2, timeouts: 1,
    latencyTotalMillis: 30, averageLatencyMillis: 15, status: '通过' });
});
