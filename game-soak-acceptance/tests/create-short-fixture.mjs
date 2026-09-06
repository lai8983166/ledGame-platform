// Developer fixture preparation ONLY. Never shipped in the portable tool.
// The formal runner never changes a game document or shortens its duration.
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { launchPackagedGame } from '../src/packaged-app.mjs';
import { readCatalog } from '../src/preflight.mjs';

const input = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
if (input.hardwareMode !== 'simulated') throw new Error('测试样本只能在明确 simulated 的隔离副本中创建');
const app = await launchPackagedGame({ ...input, backendPort: 0 });
let targets;
try {
  const base = `http://127.0.0.1:${app.runtime.backendPort}`;
  const catalog = await readCatalog(base);
  targets = ['simple', 'normal', 'diffcult', 'rank-type1'].map((name) => {
    const entry = catalog.find((item) => item.summary.name === name);
    assert(entry, `制作开发夹具需要实际存在的玩法 ${name}`);
    return entry;
  });
  for (const entry of targets.filter((item) => item.summary.type === 'default')) {
    const document = structuredClone(entry.document);
    document.globalTimeLimit = true; document.globalTimeLimitValue = 15;
    const response = await fetch(`${base}/game-editor/${entry.summary.id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(document), signal: AbortSignal.timeout(10000),
    });
    const result = await response.json();
    assert(response.ok && result.code === 200, JSON.stringify(result));
  }
  // Packaged close terminates its embedded Java. Allow H2's default delayed file flush
  // before stopping this fixture writer; the next isolated launch verifies persisted values.
  await new Promise((resolve) => setTimeout(resolve, 1500));
} finally { await app.close(); }
const config = { ...input, runId: '', backendPort: 0, durationHours: 0.1,
  gameDatabaseSource: path.join(app.runtime.directory, 'user-data', 'database', 'runtime', 'ledgame.mv.db'),
  games: targets.map((entry) => ({ gameId: entry.summary.id, playerCount: 1, startLevelIndex: 0 })),
};
const file = path.join(app.runtime.directory, 'developer-multi-game.json');
await fs.writeFile(file, JSON.stringify(config, null, 2));
console.log(`开发测试夹具配置：${file}\n仅副本中 simple/normal/diffcult 时长为 15 秒，Rank 原样。原库哈希已复核。`);
