import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderReport } from '../src/report.mjs';

test('Chinese report distinguishes complete, failed, interrupted and unverified', () => {
  const config = { durationHours: 18, hardwareMode: 'real', limits: { memoryLimitMB: 1000, memoryGrowthMBPerHour: 10 } };
  const result = { status: '完成', rounds: 2, counts: { 41: 2 }, elapsedMillis: 64800000, runningMillis: 60000000,
    monitoring: { memory: { status: '通过' }, communication: { status: '通过' } } };
  assert.match(renderReport(config, result), /结果：通过/);
  assert.match(renderReport(config, { ...result, status: '失败', error: '窗口无响应' }), /结果：失败/);
  assert.match(renderReport(config, { ...result, status: '未完成' }), /结果：未完成/);
  assert.match(renderReport(config, { ...result, monitoring: {} }), /结果：部分未验证/);
  const short = renderReport({ ...config, durationHours: 0.03, hardwareMode: 'simulated' }, { ...result, monitoring: {} });
  assert.match(short, /短时测试，不代表 18 小时/);
  assert.match(short, /真实通信判定：未验证/);
  assert.match(short, /实际接收报文：未取得/);
});
