import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Readable, Writable } from 'node:stream';
import { SOAK_PROFILES, buildWizardConfig, catalogChoices, parseProfileInput, parseToggleInput, renderChoices,
  runInteractiveWizard, selectionTargets, toggleSelection } from '../src/wizard.mjs';
import { discoverRealHardwareConfig } from '../src/hardware-config.mjs';
import { discoverGameDatabaseSource } from '../src/game-database.mjs';

const entry = (id, name, duration = 60) => ({
  summary: { id, name, displayName: name, type: name.toLowerCase() === 'rank' ? 'rank' : 'default', minPlayers: 1, maxPlayers: 4 },
  document: { id, globalTimeLimit: true, globalTimeLimitValue: duration,
    levels: [{ label: '一', ...(name.toLowerCase() === 'rank' ? { durationSeconds: duration } : {}) }] },
});

test('向导固定档位和选择输入可验证', () => {
  assert.deepEqual(SOAK_PROFILES.map((profile) => profile.key), ['quick', 'standard', 'two-hours', 'formal']);
  assert.equal(SOAK_PROFILES[2].durationHours, 2);
  assert.equal(SOAK_PROFILES[3].durationHours, 18);
  assert.equal(parseProfileInput('3'), 'two-hours');
  assert.equal(parseProfileInput('2h'), 'two-hours');
  assert.equal(parseProfileInput('4'), 'formal');
  assert.deepEqual(parseToggleInput('1, 3', 3), { done: false, indexes: [1, 3] });
  assert.deepEqual(parseToggleInput('done', 3), { done: true, indexes: [] });
  assert.throws(() => parseToggleInput('0', 3), /编号/);
});

test('游戏默认全部未选，支持多选/取消且拒绝空选择', () => {
  const choices = catalogChoices([entry(41, 'Simple'), entry(42, 'Normal'), entry(43, 'Rank')]);
  assert.match(renderChoices(choices), /\[ \] 1/);
  let selected = toggleSelection(new Set(), [1, 3]);
  assert.deepEqual(selectionTargets(choices, selected).map((target) => target.gameId), [41, 43]);
  selected = toggleSelection(selected, [1]);
  assert.deepEqual(selectionTargets(choices, selected).map((target) => target.gameId), [43]);
  assert.throws(() => selectionTargets(choices, new Set()), /至少选择一个/);
});

test('不可测试游戏显示原因且不能被选择', () => {
  const infinite = entry(44, 'simple', null);
  const choices = catalogChoices([entry(41, 'Simple'), infinite]);
  assert.equal(choices[0].selectable, true);
  assert.equal(choices[1].selectable, false);
  assert.match(renderChoices(choices), /不可选/);
  assert.throws(() => selectionTargets(choices, new Set([2])), /不可选择/);
});

test('向导配置保留真实 ID、稳定选择顺序和未选择列表', () => {
  const large = entry(9007199254740993n.toString(), 'simple'); large.summary.displayName = '大 ID';
  const choices = catalogChoices([large, entry(42, 'Normal')]);
  const config = buildWizardConfig({ gameExecutable: 'F:/game/LED Game.exe', outputRoot: 'F:/runs', hardwareMode: 'simulated',
    floor: { width: 8, height: 8 }, profile: 'standard', choices, selected: new Set([1]) });
  assert.equal(config.games[0].gameId, '9007199254740993');
  assert.deepEqual(config.soakWizard.selectedGameIds, ['9007199254740993']);
  assert.deepEqual(config.soakWizard.unselectedGameIds, [42]);
  assert.equal(config.durationHours, 0.5);
  assert.equal(config.limits.memoryLimitMB, 8192);
  assert.equal(config.limits.memoryGrowthMBPerHour, null);
  const realConfig = buildWizardConfig({ gameExecutable: 'F:/game/LED Game.exe', hardwareMode: 'real', floor: { width: 8, height: 8 },
    profile: 'quick', choices, selected: new Set([1]) });
  assert.equal(realConfig.floor.configDirectory, path.resolve('F:/game/elc408'));
});

test('自动发现本机游戏数据库并拒绝正在使用的数据库', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-database-source-'));
  try {
    const exe = path.join(root, 'LED Game.exe');
    const appData = path.join(root, 'appdata');
    const source = path.join(appData, 'led-game', 'database', 'runtime', 'ledgame.mv.db');
    await fs.mkdir(path.dirname(source), { recursive: true });
    await fs.writeFile(exe, 'fixture');
    await fs.writeFile(source, 'database');
    const discovered = await discoverGameDatabaseSource(exe, { appData, localAppData: path.join(root, 'local') });
    assert.equal(discovered.path, source);
    assert.equal(discovered.bytes, 8);
    await fs.writeFile(source.replace('.mv.db', '.lock.db'), 'locked');
    await assert.rejects(
      () => discoverGameDatabaseSource(exe, { appData, localAppData: path.join(root, 'local') }),
      /正在使用.*先关闭游戏端/,
    );
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('真实地砖配置自动读取 exe 同级 elc408 和 wiring 尺寸', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-hardware-config-'));
  try {
    const exe = path.join(root, 'LED Game.exe');
    const directory = path.join(root, 'elc408');
    await fs.mkdir(directory);
    await fs.writeFile(exe, 'fixture');
    await fs.writeFile(path.join(directory, 'conf.json'), JSON.stringify({ tcpServerPort: 3003 }));
    await fs.writeFile(path.join(directory, 'wiring.json'), JSON.stringify({ lines: [[[0, 0], [1, 0]], [[1, 1], [0, 1]]] }));
    const discovered = await discoverRealHardwareConfig(exe);
    assert.equal(discovered.configDirectory, directory);
    assert.deepEqual(discovered.floor, { width: 2, height: 2 });
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('交互式向导完成真实列表多选后才执行', async () => {
  let outputText = '';
  const output = new Writable({ write(chunk, encoding, callback) { outputText += chunk.toString(); callback(); } });
  let executed = null;
  const answers = ['8', '8', '1, 3', 'done'];
  const result = await runInteractiveWizard({
    input: Readable.from([]), output, ask: async () => answers.shift(),
    defaults: { gameExecutable: 'F:/game/LED Game.exe', hardwareMode: 'simulated', profile: 'quick', confirm: 'yes', outputRoot: 'F:/runs' },
    databaseOptions: { appData: 'F:/missing-appdata', localAppData: 'F:/missing-localappdata' },
    loadCatalog: async () => [entry(41, 'Simple'), entry(42, 'Normal'), entry(43, 'Rank')],
    execute: async (config) => { executed = config; return { directory: 'F:/runs/soak-1', result: { status: '完成' } }; },
  });
  assert.equal(result.cancelled, false);
  assert.deepEqual(executed.soakWizard.selectedGameIds, [41, 43]);
  assert.match(outputText, /默认全部未选/);
  assert.match(outputText, /已选择游戏：41, 43/);
});

test('真实硬件向导不再询问地砖尺寸或配置目录', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-wizard-real-'));
  try {
    const executable = path.join(root, 'LED Game.exe');
    const directory = path.join(root, 'elc408');
    await fs.mkdir(directory);
    await fs.writeFile(executable, 'fixture');
    await fs.writeFile(path.join(directory, 'conf.json'), JSON.stringify({ tcpServerPort: 3003 }));
    await fs.writeFile(path.join(directory, 'wiring.json'), JSON.stringify({ width: 8, height: 8 }));
    let executed = null;
    const answers = ['1', 'done'];
    const output = new Writable({ write(chunk, encoding, callback) { callback(); } });
    await runInteractiveWizard({
      input: Readable.from([]), output, ask: async () => answers.shift(),
      defaults: { gameExecutable: executable, hardwareMode: 'real', profile: 'quick', confirm: 'yes', outputRoot: path.join(root, 'runs') },
      databaseOptions: { appData: path.join(root, 'missing-appdata'), localAppData: path.join(root, 'missing-localappdata') },
      loadCatalog: async ({ floor }) => {
        assert.deepEqual(floor, { width: 8, height: 8 });
        return [entry(41, 'Simple')];
      },
      execute: async (config) => { executed = config; return { directory: path.join(root, 'runs', 'soak-1'), result: { status: '完成' } }; },
    });
    assert.equal(executed.floor.configDirectory, directory);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('向导把本机数据库来源传给列表读取和隔离执行', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-wizard-database-'));
  try {
    const executable = path.join(root, 'LED Game.exe');
    const source = path.join(root, 'appdata', 'led-game', 'database', 'runtime', 'ledgame.mv.db');
    await fs.mkdir(path.dirname(source), { recursive: true });
    await fs.writeFile(executable, 'fixture');
    await fs.writeFile(source, 'database');
    let loadedSource;
    let executed;
    const answers = ['8', '8', '1', 'done'];
    await runInteractiveWizard({
      input: Readable.from([]), output: new Writable({ write(chunk, encoding, callback) { callback(); } }),
      ask: async () => answers.shift(),
      defaults: { gameExecutable: executable, hardwareMode: 'simulated', profile: 'quick', confirm: 'yes', outputRoot: path.join(root, 'runs') },
      databaseOptions: { appData: path.join(root, 'appdata'), localAppData: path.join(root, 'missing-localappdata') },
      loadCatalog: async ({ gameDatabaseSource }) => {
        loadedSource = gameDatabaseSource;
        return [entry(41, 'Simple')];
      },
      execute: async (config) => { executed = config; return { directory: path.join(root, 'runs', 'soak-1'), result: { status: '完成' } }; },
    });
    assert.equal(loadedSource, source);
    assert.equal(executed.gameDatabaseSource, source);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
