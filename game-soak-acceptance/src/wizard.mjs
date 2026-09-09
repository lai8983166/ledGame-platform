import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs/promises';
import { createInterface } from 'node:readline/promises';
import { stdin as defaultInput, stdout as defaultOutput } from 'node:process';
import { effectiveDuration } from './preflight.mjs';
import { defaultHardwareConfigDirectory, discoverRealHardwareConfig } from './hardware-config.mjs';
import { discoverGameDatabaseSource } from './game-database.mjs';

export const SOAK_PROFILES = Object.freeze([
  { key: 'quick', label: '快速测试', durationHours: 5 / 60 },
  { key: 'standard', label: '标准测试', durationHours: 0.5 },
  { key: 'formal', label: '正式 18 小时烤机', durationHours: 18 },
]);

const finiteInteger = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const SUPPORTED_SIMPLE_NAMES = new Set(['simple-demo', 'simple', 'normal', 'diffcult']);

export function parseToggleInput(value, count) {
  if (typeof value !== 'string') throw new Error('请选择游戏编号');
  const trimmed = value.trim();
  if (trimmed.toLowerCase() === 'done' || trimmed === '完成' || trimmed === '确认') return { done: true, indexes: [] };
  const indexes = trimmed.split(/[\s,，;；]+/).filter(Boolean).map((token) => Number(token));
  if (!indexes.length || indexes.some((index) => !finiteInteger(index, 1, count))) {
    throw new Error(`请输入 1–${count} 之间的游戏编号，多个编号用逗号分隔，完成后输入 done`);
  }
  return { done: false, indexes: [...new Set(indexes)] };
}

export function toggleSelection(selected, indexes) {
  const next = new Set(selected);
  for (const index of indexes) {
    if (next.has(index)) next.delete(index);
    else next.add(index);
  }
  return next;
}

function entryReason(entry, floor) {
  if (!entry?.summary) return '游戏详情不完整';
  const { summary, document } = entry;
  if (!summary.id || !/^[1-9][0-9]{0,18}$/.test(String(summary.id))) return '游戏 ID 无效';
  if (!(summary.type === 'rank' || (summary.type === 'default' && SUPPORTED_SIMPLE_NAMES.has(String(summary.name).toLowerCase())))) return '该玩法暂不支持烤机';
  if (!document) return entry.catalogError || '游戏详情不完整';
  if (!finiteInteger(summary.minPlayers, 1, 6) || !finiteInteger(summary.maxPlayers, summary.minPlayers, 6)) return '支持人数配置无效';
  if (!Array.isArray(document.levels) || document.levels.length === 0) return '没有可用关卡';
  if (floor && ((summary.width !== undefined && summary.width !== floor.width)
    || (summary.height !== undefined && summary.height !== floor.height)
    || (document.siteSizeWidth !== undefined && document.siteSizeWidth !== floor.width)
    || (document.siteSizeHeight !== undefined && document.siteSizeHeight !== floor.height))) return '地砖尺寸不匹配';
  try { effectiveDuration(entry); }
  catch (error) { return error.message || '没有可确认的有限整局时长'; }
  return null;
}

export function catalogChoices(catalog, floor) {
  if (!Array.isArray(catalog)) throw new Error('游戏列表格式错误');
  return catalog.map((entry, position) => {
    const summary = entry?.summary ?? {};
    const document = entry?.document ?? {};
    let durationSeconds = null;
    try { durationSeconds = effectiveDuration(entry); } catch { /* reason is exposed below */ }
    const reason = entryReason(entry, floor);
    return {
      index: position + 1,
      gameId: summary.id,
      name: summary.displayName || summary.name || `游戏 ${summary.id ?? '?'}`,
      type: summary.type || 'unknown',
      levels: Array.isArray(document.levels) ? document.levels.length : 0,
      minPlayers: summary.minPlayers,
      maxPlayers: summary.maxPlayers,
      durationSeconds,
      selectable: !reason,
      reason,
      entry,
    };
  });
}

export function selectionTargets(choices, selected) {
  const indexes = [...selected].sort((a, b) => a - b);
  if (!indexes.length) throw new Error('至少选择一个可测试游戏');
  const byIndex = new Map(choices.map((choice) => [choice.index, choice]));
  return indexes.map((index) => {
    const choice = byIndex.get(index);
    if (!choice || !choice.selectable) throw new Error(`游戏 ${index} 不可选择：${choice?.reason || '编号无效'}`);
    return { gameId: choice.gameId, playerCount: Math.max(1, choice.minPlayers), startLevelIndex: 0 };
  });
}

export function buildWizardConfig({ gameExecutable, outputRoot, hardwareMode, floor, configDirectory,
  gameDatabaseSource, profile, choices, selected }) {
  if (!gameExecutable?.trim()) throw new Error('必须填写游戏端 exe 路径');
  const chosenProfile = SOAK_PROFILES.find((item) => item.key === profile);
  if (!chosenProfile) throw new Error('测试档位无效');
  const games = selectionTargets(choices, selected);
  if (!['real', 'simulated'].includes(hardwareMode)) throw new Error('硬件模式无效');
  if (!floor || !finiteInteger(floor.width, 1, 256) || !finiteInteger(floor.height, 1, 256)) throw new Error('地砖尺寸必须为 1–256 的整数');
  const resolvedConfigDirectory = hardwareMode === 'real'
    ? (configDirectory?.trim() || defaultHardwareConfigDirectory(gameExecutable))
    : '';
  const chosenIds = new Set(games.map((game) => String(game.gameId)));
  return {
    runId: '',
    outputRoot: outputRoot || path.join(process.env.LEDGAME_SOAK_TOOL_ROOT || process.cwd(), 'runs'),
    gameExecutable: gameExecutable.trim(),
    ...(gameDatabaseSource?.trim() ? { gameDatabaseSource: gameDatabaseSource.trim() } : {}),
    games, durationHours: chosenProfile.durationHours, hardwareMode,
    floor: { ...floor, ...(hardwareMode === 'real' ? { configDirectory: resolvedConfigDirectory } : {}) },
    simulatedInputEnabled: false,
    limits: { actionSeconds: 10, startupSeconds: 60, settleSeconds: 30, memoryLimitMB: null, memoryGrowthMBPerHour: null },
    soakWizard: { profile: chosenProfile.key, profileLabel: chosenProfile.label,
      selectedGameIds: games.map((game) => game.gameId),
      unselectedGameIds: choices.filter((choice) => !chosenIds.has(String(choice.gameId))).map((choice) => choice.gameId) },
  };
}

export function renderChoices(choices, selected = new Set()) {
  return choices.map((choice) => {
    const mark = selected.has(choice.index) ? 'x' : ' ';
    const duration = Number.isFinite(choice.durationSeconds) ? `${choice.durationSeconds} 秒` : '不可确定';
    const players = Number.isSafeInteger(choice.minPlayers) ? `${choice.minPlayers}–${choice.maxPlayers} 人` : '人数未知';
    const suffix = choice.selectable ? '' : `；不可选：${choice.reason}`;
    return `[${mark}] ${choice.index}. ${choice.name}（ID: ${choice.gameId ?? '?'}；${players}；${choice.levels} 关；${duration}）${suffix}`;
  }).join('\n');
}

async function askNumber(ask, label, fallback, min, max) {
  const raw = await ask(`${label}（${min}–${max}，直接回车使用 ${fallback}）：`);
  if (!raw.trim()) return fallback;
  const value = Number(raw.trim());
  if (!finiteInteger(value, min, max)) throw new Error(`${label}必须是 ${min}–${max} 的整数`);
  return value;
}

export async function runInteractiveWizard({ input = defaultInput, output = defaultOutput,
  loadCatalog, execute, defaults = {}, toolRoot = process.env.LEDGAME_SOAK_TOOL_ROOT || process.cwd(),
  databaseOptions = {}, ask: askOverride } = {}) {
  if (typeof loadCatalog !== 'function' || typeof execute !== 'function') throw new Error('向导依赖未配置');
  const rl = createInterface({ input, output });
  const ask = askOverride || (async (question) => rl.question(question));
  const write = (text = '') => output.write(`${text}\n`);
  try {
    write('=== 游戏端烤机测试向导 ===');
    const executable = (defaults.gameExecutable || await ask('游戏端 exe 路径：')).trim();
    if (!executable) throw new Error('必须填写游戏端 exe 路径');
    const database = defaults.gameDatabaseSource?.trim()
      ? { path: path.resolve(defaults.gameDatabaseSource.trim()), source: '指定' }
      : await discoverGameDatabaseSource(executable, databaseOptions);
    if (database) write(`已自动使用本机游戏数据库：${database.path}`);
    else write('未找到本机游戏数据库，将使用游戏包内初始数据库。');
    const hardwareAnswer = (defaults.hardwareMode || await ask('硬件模式（1=无硬件模拟，2=真实地砖，默认 1）：')).trim().toLowerCase();
    const hardwareMode = hardwareAnswer === '2' || hardwareAnswer === 'real' ? 'real' : 'simulated';
    let floor;
    let configDirectory = '';
    if (hardwareMode === 'real') {
      const discovered = await discoverRealHardwareConfig(executable);
      floor = discovered.floor;
      configDirectory = defaults.configDirectory?.trim() || discovered.configDirectory;
      write(`已自动找到真实地砖配置，尺寸：${floor.width}×${floor.height}`);
    } else {
      const width = await askNumber(ask, '地砖宽度', defaults.width ?? 8, 1, 256);
      const height = await askNumber(ask, '地砖高度', defaults.height ?? 8, 1, 256);
      floor = { width, height };
    }
    write('正在读取游戏端实际游戏列表，请稍候……');
    const catalog = await loadCatalog({ gameExecutable: executable, gameDatabaseSource: database?.path,
      hardwareMode: 'simulated', floor });
    const choices = catalogChoices(catalog, floor);
    if (!choices.length) throw new Error('游戏端没有可识别的游戏');
    let selected = new Set();
    while (true) {
      write('\n可参与本轮测试的游戏（默认全部未选）：');
      write(renderChoices(choices, selected));
      const raw = await ask('\n输入要切换的编号（如 1,3），完成选择请输入 done：');
      const parsed = parseToggleInput(raw, choices.length);
      if (parsed.done) {
        try { selectionTargets(choices, selected); break; } catch (error) { write(`选择无效：${error.message}`); }
      } else {
        for (const index of parsed.indexes) if (!choices[index - 1].selectable) write(`已忽略不可选游戏 ${index}：${choices[index - 1].reason}`);
        selected = toggleSelection(selected, parsed.indexes.filter((index) => choices[index - 1].selectable));
      }
    }
    write('\n测试档位：1=快速 5 分钟，2=标准 30 分钟，3=正式 18 小时');
    const profileAnswer = (defaults.profile || await ask('选择测试档位（默认 1）：')).trim();
    const profile = profileAnswer === '3' || profileAnswer === 'formal' ? 'formal' : profileAnswer === '2' || profileAnswer === 'standard' ? 'standard' : 'quick';
    const config = buildWizardConfig({ gameExecutable: executable, outputRoot: defaults.outputRoot || path.join(toolRoot, 'runs'), hardwareMode,
      floor, configDirectory, gameDatabaseSource: database?.path, profile, choices, selected });
    write('\n即将运行：');
    write(`- 已选择游戏：${config.soakWizard.selectedGameIds.join(', ')}`);
    write(`- 未选择游戏：${config.soakWizard.unselectedGameIds.length ? config.soakWizard.unselectedGameIds.join(', ') : '无'}`);
    write(`- 模式：${hardwareMode === 'real' ? '真实地砖' : '无硬件模拟'}；档位：${config.soakWizard.profileLabel}`);
    write(`- 地砖：${floor.width}×${floor.height}${hardwareMode === 'real' ? '；自动使用 exe 同级 elc408' : ''}`);
    const confirmed = (defaults.confirm || await ask('确认开始？输入 yes 开始，其他内容取消：')).trim().toLowerCase();
    if (confirmed !== 'yes' && confirmed !== 'y' && confirmed !== '是') { write('已取消，本次未启动烤机。'); return { cancelled: true, config }; }
    return { cancelled: false, config, result: await execute(config) };
  } finally { rl.close(); }
}

export async function loadCatalogFromPackagedGame(config, { launch, readCatalog } = {}) {
  if (typeof launch !== 'function' || typeof readCatalog !== 'function') throw new Error('游戏列表读取依赖未配置');
  const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgame-soak-catalog-'));
  let app;
  try {
    app = await launch({ ...config, outputRoot: temporaryRoot, runId: `catalog-${Date.now()}`, hardwareMode: 'simulated' });
    return await readCatalog(`http://127.0.0.1:${app.runtime.backendPort}`);
  } finally {
    try { await app?.close(); } finally { await fs.rm(temporaryRoot, { recursive: true, force: true }); }
  }
}
