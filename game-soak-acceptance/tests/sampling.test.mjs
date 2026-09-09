import { test } from 'node:test';
import assert from 'node:assert/strict';
import { shouldRunDiscoveryProbe } from '../src/sampling.mjs';

test('discovery probes start after the read-only startup sample only for real hardware', () => {
  assert.equal(shouldRunDiscoveryProbe('real', 0), false);
  assert.equal(shouldRunDiscoveryProbe('real', 1), true);
  assert.equal(shouldRunDiscoveryProbe('simulated', 1), false);
  assert.equal(shouldRunDiscoveryProbe('real', -1), false);
});
