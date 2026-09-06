import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FloorDevice } from '../src/floor-device.mjs';

test('frame totals keep growing but stored frame evidence is bounded', () => {
  const device = new FloorDevice(8, 8);
  const frame = Buffer.alloc(5 + 8 * 8 * 3); frame[0] = 0x67; frame.writeUInt32BE(192, 1);
  for (let i = 0; i < 100000; i++) { device.consume(frame.subarray(0, 7)); device.consume(frame.subarray(7)); }
  assert.equal(device.snapshot().validFrames, 100000);
  assert.equal(device.snapshot().bufferedBytes, 0);
  assert.equal(device.snapshot().rejectedFrames, 0);
  device.consume(Buffer.from([0x67, 0xFF, 0xFF, 0xFF, 0xFF]));
  assert.equal(device.snapshot().rejectedFrames, 1);
  assert.equal(device.snapshot().source, 'simulated-tcp');
});

test('idle size is legitimate, transition grace is bounded, wrong running size fails', () => {
  let at = 0; const device = new FloorDevice(16, 36, () => at);
  const frame = (size) => { const data = Buffer.alloc(size + 5); data[0] = 0x67; data.writeUInt32BE(size, 1); return data; };
  device.observeState({ engineState: 'IDLE', width: 16, height: 16 });
  device.consume(frame(768));
  device.observeState({ engineState: 'RUNNING', width: 16, height: 36 });
  device.consume(frame(768));
  device.consume(frame(1728));
  assert.equal(device.snapshot().transitionFrames, 1);
  assert.equal(device.snapshot().runningFrames, 1);
  assert.equal(device.snapshot().rejectedFrames, 0);
  at = 1001; device.consume(frame(768));
  assert.equal(device.snapshot().rejectedFrames, 1);
  device.observeState({ engineState: 'STOPPED', width: 16, height: 36 });
  device.consume(frame(768)); // idle frame can arrive before the next read-only state poll
  assert.equal(device.snapshot().rejectedFrames, 1);
  assert.equal(device.snapshot().transitionFrames, 2);
});
