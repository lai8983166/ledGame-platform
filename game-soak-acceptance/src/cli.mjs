import { readFile } from 'node:fs/promises';
import { buildPlan, effectiveDuration, readCatalog } from './preflight.mjs';
import { runAcceptance } from './run.mjs';
import { launchPackagedGame } from './packaged-app.mjs';
import { injectFromRun } from './inject.mjs';
import { loadCatalogFromPackagedGame, runInteractiveWizard } from './wizard.mjs';

function printCatalog(catalog) {
  console.table(catalog.map((entry) => {
    let duration;
    try { duration = `${effectiveDuration(entry)} 秒`; } catch { duration = '无限或无法确认，不可烤机'; }
    return { ID: entry.summary.id, 名称: entry.summary.displayName || entry.summary.name,
      类型: entry.summary.type, 整局时长: duration, 关卡数: entry.document.levels?.length ?? 0,
      人数: `${entry.summary.minPlayers}–${entry.summary.maxPlayers}` };
  }));
}

try {
  const [command, baseUrl, configFile] = process.argv.slice(2);
  if (command === 'inject') {
    console.log(await injectFromRun(baseUrl, configFile, Number(process.argv[5]), Number(process.argv[6])));
  } else if (command === 'wizard') {
    const result = await runInteractiveWizard({
      defaults: baseUrl && baseUrl.toLowerCase().endsWith('.exe') ? { gameExecutable: baseUrl } : {},
      loadCatalog: (config) => loadCatalogFromPackagedGame(config, {
        launch: launchPackagedGame,
        readCatalog: (baseUrl) => readCatalog(baseUrl, fetch, { includeUnsupported: true }),
      }),
      execute: async (config) => {
        const controller = new AbortController();
        const abort = () => controller.abort(new Error('用户中止'));
        process.once('SIGINT', abort); process.once('SIGTERM', abort);
        try { return await runAcceptance(config, { signal: controller.signal }); }
        finally { process.off('SIGINT', abort); process.off('SIGTERM', abort); }
      },
    });
    if (result.cancelled) process.exitCode = 0;
    else if (result.result?.result?.status !== '完成') process.exitCode = 1;
  } else if (['run', 'inspect'].includes(command) && baseUrl) {
    const config = JSON.parse(await readFile(baseUrl, 'utf8'));
    if (command === 'run') {
      const controller = new AbortController();
      const abort = () => controller.abort(new Error('用户中止'));
      process.once('SIGINT', abort); process.once('SIGTERM', abort);
      try {
        const { result } = await runAcceptance(config, { signal: controller.signal });
        if (result.status !== '完成' || result.monitoring?.memory?.status === '失败' || result.monitoring?.communication?.status === '失败') process.exitCode = 1;
      } finally { process.off('SIGINT', abort); process.off('SIGTERM', abort); }
    } else {
      // Uses the same isolated packaged database, but never enters a game.
      const app = await launchPackagedGame(config);
      try { printCatalog(await readCatalog(`http://127.0.0.1:${app.runtime.backendPort}`)); }
      finally { await app.close(); }
    }
  } else {
  if (!['list', 'preflight'].includes(command) || !baseUrl || (command === 'preflight' && !configFile)) {
    throw new Error('用法：soak.cmd inspect <配置文件> | run <配置文件> | list <后端地址> | preflight <后端地址> <配置文件>。inspect 仅启动隔离游戏端读取列表；list/preflight 不启动程序。');
  }
  const catalog = await readCatalog(baseUrl);
  if (command === 'list') {
    printCatalog(catalog);
  } else {
    const plan = buildPlan(JSON.parse(await readFile(configFile, 'utf8')), catalog);
    console.log(JSON.stringify({ 说明: '只读预检通过，尚未执行烤机', 目标小时: plan.config.durationHours,
      顺序: plan.games.map(({ gameId, name, playerCount, startLevelIndex, durationSeconds, fingerprint }) =>
        ({ gameId, name, playerCount, startLevelIndex, durationSeconds, fingerprint })),
      单次列表预算秒: plan.cycleUpperSeconds }, null, 2));
  }
  }
} catch (error) {
  console.error(`测试工具错误：${error.message}`);
  process.exitCode = 1;
}
