import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { checkEvidenceBudget } from '../src/evidence-budget.mjs';

test('evidence limits cover product logs and never remove evidence on overflow', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'soak-budget-'));
  try {
    await fs.mkdir(path.join(directory, 'logs'));
    await fs.writeFile(path.join(directory, 'logs', 'backend.log'), 'x'.repeat(10000));
    assert.equal((await checkEvidenceBudget(directory)).bytes, 10000);
    await assert.rejects(() => checkEvidenceBudget(directory, { maxBytes: 9999 }), /证据上限/);
    await assert.rejects(() => checkEvidenceBudget(directory, { maxFiles: 1 }), /文件数量/);
    await assert.rejects(() => checkEvidenceBudget(directory, { minFreeBytes: Number.MAX_SAFE_INTEGER }), /可用空间不足/);
    assert.equal((await fs.stat(path.join(directory, 'logs', 'backend.log'))).size, 10000);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
