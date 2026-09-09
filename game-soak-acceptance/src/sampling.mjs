import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import fs from 'node:fs/promises';
import path from 'node:path';
import { MemoryMonitor, ProcessMonitor, CommunicationMonitor } from './monitor.mjs';
import { checkEvidenceBudget } from './evidence-budget.mjs';

const execFileAsync = promisify(execFile);
export function shouldRunDiscoveryProbe(hardwareMode, completedSamples) {
  return hardwareMode === 'real' && Number.isSafeInteger(completedSamples) && completedSamples > 0;
}

export async function sampleProcessTree(pid) {
  if (!Number.isInteger(pid) || pid <= 0) throw new Error('采样 PID 非法');
  const result = await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-File',
    fileURLToPath(new URL('./sample-processes.ps1', import.meta.url)), '-RootPid', String(pid)],
  { windowsHide: true, timeout: 8000, maxBuffer: 1048576 });
  const rows = JSON.parse(result.stdout.replace(/^\uFEFF/, ''));
  if (!Array.isArray(rows)) throw new Error('进程采样格式错误');
  return rows;
}

export async function assertProcessesGone(identities) {
  if (!identities?.length) throw new Error('没有进程身份，无法确认清理完成');
  for (let attempt = 0; attempt < 4; attempt++) {
    const { stdout } = await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-File',
      fileURLToPath(new URL('./sample-processes.ps1', import.meta.url)), '-RootPid', '0', '-ExpectedIdentitiesJson', JSON.stringify(identities)],
    { windowsHide: true, timeout: 8000, maxBuffer: 1048576 });
    const remaining = JSON.parse(stdout.replace(/^\uFEFF/, ''));
    if (!Array.isArray(remaining)) throw new Error('进程退出核验格式错误');
    if (!remaining.length) return;
    if (attempt === 3) throw new Error(`自有进程清理后仍存在：${remaining.map((p) => p.pid).join(',')}；没有按名称终止其他程序`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

export async function startSampling(app, config) {
  const started = performance.now(); const memory = new MemoryMonitor(config.limits);
  const processes = new ProcessMonitor(); const communication = new CommunicationMonitor();
  let stopped = false; let timer; let inFlight; let failure; let sampleCount = 0; let input; let identities;
  const sample = async () => {
    app.checkAlive();
    const rows = await sampleProcessTree(app.electron.process().pid);
    identities = rows.map(({ pid, createdAt }) => ({ pid, createdAt }));
    processes.observe(rows);
    const at = performance.now() - started;
    memory.observe(at, rows);
    const state = await app.get('/engine/game/state');
    const phase = { engineState: state.engineState, sessionId: state.sessionId, gameId: state.gameId,
      runtimeMode: state.runtimeMode, runningMillis: state.runningMillis };
    // The immediate startup sample stays read-only; real-hardware discovery
    // probes begin with the first 10-second sample and then follow the sampler.
    if (shouldRunDiscoveryProbe(config.hardwareMode, sampleCount)) {
      await app.get('/hardware/elc408/acceptance/search-probe');
    }
    const metrics = await app.get('/hardware/elc408/metrics'); communication.observe(metrics);
    if (config.simulatedInputEnabled) {
      input = await app.get('/acceptance/soak/input');
      if (input.enabled !== true || !Number.isSafeInteger(input.simulatedEvents)) throw new Error('模拟输入证据缺失');
    }
    // Responsiveness probe is read-only; never clicks the game or enables DebugPanel.
    for (const page of app.electron.windows()) {
      let deadline;
      try {
        await Promise.race([page.evaluate(() => document.readyState), new Promise((_, reject) => {
          deadline = setTimeout(() => reject(new Error('渲染窗口响应超时')), 5000);
        })]);
      } finally { clearTimeout(deadline); }
    }
    if (++sampleCount > 61000) throw new Error('采样文件上限已到，停止而不是丢弃证据');
    const evidence = await checkEvidenceBudget(app.runtime.directory);
    await fs.appendFile(path.join(app.runtime.directory, 'samples.jsonl'), `${JSON.stringify({
      at, time: new Date().toISOString(), phase, processes: rows, sdk: metrics, evidence,
      tester: { pid: process.pid, ...process.memoryUsage() }, floor: app.floor?.snapshot(), input,
    })}\n`);
  };
  await sample();
  const schedule = (delay = 10000) => {
    timer = setTimeout(() => {
      const sampleStarted = performance.now();
      inFlight = sample().catch((error) => { failure = error; }).finally(() => {
        if (!stopped && !failure) schedule(Math.max(0, 10000 - (performance.now() - sampleStarted)));
      });
    }, delay);
  };
  schedule();
  return {
    check() { if (failure) throw failure; },
    identities() { return identities; },
    summary() { return { memory: memory.summary(), communication: communication.summary(config.hardwareMode), input, failure: failure?.message }; },
    async stop() { stopped = true; clearTimeout(timer); await inFlight; },
  };
}
