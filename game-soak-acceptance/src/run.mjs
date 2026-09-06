import fs from 'node:fs/promises';
import path from 'node:path';
import { prepareIsolatedRuntime } from './isolated-runtime.mjs';
import { launchPackagedGame, openTouchUi } from './packaged-app.mjs';
import { normalizeConfig, readCatalog, buildPlan, assertUnchanged } from './preflight.mjs';
import { runLoop } from './loop.mjs';
import { playUiRound } from './ui-round.mjs';
import { startSampling, assertProcessesGone } from './sampling.mjs';
import { renderReport, memoryChart } from './report.mjs';

// Dependencies are replaceable only in unit tests, never by the customer configuration.
export async function runAcceptance(input, { signal, log = console.log, dependencies = {} } = {}) {
  const config = normalizeConfig(input);
  const prepare = dependencies.prepare ?? prepareIsolatedRuntime;
  const launch = dependencies.launch ?? launchPackagedGame;
  const catalogReader = dependencies.catalog ?? readCatalog;
  const open = dependencies.open ?? openTouchUi;
  const sample = dependencies.sample ?? startSampling;
  const play = dependencies.play ?? playUiRound;
  const loop = dependencies.loop ?? runLoop;
  const runtime = await prepare(config);
  let app; let page; let sampling;
  let result = { status: '未完成', rounds: 0, counts: {} };
  const fail = (error) => {
    result.status = signal?.aborted ? '未完成' : '失败';
    result.error = [result.error, error.message ?? String(error)].filter(Boolean).join('；').slice(0, 4000);
  };
  await fs.writeFile(path.join(runtime.directory, 'config.json'), JSON.stringify(config, null, 2));
  try {
    signal?.throwIfAborted();
    log(`本轮目录：${runtime.directory}`);
    app = await launch(config, runtime);
    const base = `http://127.0.0.1:${runtime.backendPort}`;
    let catalog = await catalogReader(base);
    const plan = buildPlan(config, catalog);
    await fs.writeFile(path.join(runtime.directory, 'plan.json'), JSON.stringify(plan, null, 2));
    page = await open(app);
    sampling = await sample(app, config);
    catalog = await catalogReader(base); assertUnchanged(plan, catalog);
    result = await loop(plan, {
      signal,
      checkContent: async () => {
        app.checkAlive(); sampling.check(); assertUnchanged(plan, await catalogReader(base));
      },
      play: (target, onRunning) => play(page, target, {
        readState: () => { sampling.check(); return app.get('/engine/game/state'); },
        onRunning: () => { log(`游戏 ${target.gameId} 开始运行`); onRunning(); },
        signal, gameCount: catalog.length, actionMillis: (config.limits?.actionSeconds ?? 10) * 1000,
        settleMillis: (config.limits?.settleSeconds ?? 30) * 1000,
      }),
      writeRound: async (round) => {
        log(`游戏 ${round.gameId} 自然结束：${round.terminationReason}`);
        await fs.appendFile(path.join(runtime.directory, 'rounds.jsonl'), `${JSON.stringify(round)}\n`);
      },
    });
    app.checkAlive(); sampling.check();
  } catch (error) { fail(error); }
  finally {
    try { await sampling?.stop(); } catch (error) { fail(error); }
    if (sampling) {
      result.monitoring = sampling.summary();
      if (result.monitoring.failure) fail(new Error(result.monitoring.failure));
    }
    if (app?.floor) {
      result.floor = app.floor.snapshot();
      if (result.status === '完成' && (!result.floor.runningFrames || result.floor.rejectedFrames || result.floor.lastError)) fail(new Error('模拟输出没有有效运行帧或存在错误，见 floor 证据'));
    }
    if (result.status !== '完成' && page) {
      try { await page.screenshot({ path: path.join(runtime.directory, 'failure.png'), timeout: 5000 }); }
      catch (error) { result.screenshotError = String(error.message).slice(0, 1000); }
    }
    // A source change or failed shutdown must be reflected in the final report, not lost after writing it.
    try {
      await app?.close(); await runtime.verifySources();
      if (sampling?.identities) await assertProcessesGone(sampling.identities());
    }
    catch (error) { result.status = '失败'; result.error = `${result.error ?? ''}；清理/源文件检查失败：${error.message}`; }
    result.hardware = config.hardwareMode === 'real' ? '以实际通信采样判定' : '未验证：无硬件模拟运行';
    result.finishedAt = new Date().toISOString();
    await fs.writeFile(path.join(runtime.directory, 'result.json'), JSON.stringify(result, null, 2));
    if (result.monitoring?.memory?.windows?.length) await fs.writeFile(path.join(runtime.directory, '内存趋势.svg'), memoryChart(result.monitoring.memory.windows));
    await fs.writeFile(path.join(runtime.directory, '测试报告.md'), renderReport(config, result));
  }
  log(`结果：${result.status}；报告：${path.join(runtime.directory, '测试报告.md')}`);
  return { directory: runtime.directory, result };
}
