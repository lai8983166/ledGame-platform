import fs from 'node:fs/promises';
import { launchPackagedGame } from '../src/packaged-app.mjs';
import { readCatalog, effectiveDuration } from '../src/preflight.mjs';

// Explicit packaged-app inspection, never part of the automatic unit test glob.
const config = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
let app;
try {
  app = await launchPackagedGame(config);
  const catalog = await readCatalog(`http://127.0.0.1:${app.runtime.backendPort}`);
  await fs.writeFile(`${app.runtime.directory}/catalog.json`, JSON.stringify(catalog, null, 2));
  console.log(JSON.stringify(catalog.map((e) => {
    let duration;
    try { duration = effectiveDuration(e); } catch { duration = null; }
    return { ...e.summary, duration, levelCount: e.document.levels?.length };
  }), null, 2));
  console.log(`隔离目录：${app.runtime.directory}`);
} finally { await app?.close(); }
