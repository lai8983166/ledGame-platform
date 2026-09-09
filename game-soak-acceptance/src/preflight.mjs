import { createHash } from 'node:crypto';

const simpleNames = new Set(['simple-demo', 'simple', 'normal', 'diffcult']);
const positive = (n) => Number.isSafeInteger(n) && n > 0;
const validId = (id) => positive(id) || (typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n);
const supported = (g) => g.type === 'rank' || (g.type === 'default' && simpleNames.has(g.name));

export function normalizeConfig(input) {
  if (!Array.isArray(input?.games) || !input.games.length) throw new Error('游戏列表不能为空，请从实际游戏列表填写 ID');
  const durationHours = input.durationHours ?? 18;
  if (typeof durationHours !== 'number' || !Number.isFinite(durationHours) || durationHours <= 0 || durationHours > 168) {
    throw new Error('durationHours 必须是大于 0、不超过 168 的小时数');
  }
  const hardwareMode = input.hardwareMode ?? 'real';
  if (!['real', 'simulated'].includes(hardwareMode)) throw new Error('hardwareMode 必须为 real 或 simulated');
  if (input.simulatedInputEnabled !== undefined && typeof input.simulatedInputEnabled !== 'boolean') throw new Error('simulatedInputEnabled 必须为布尔值');
  for (const [name, value] of Object.entries(input.limits ?? {})) {
    if (!['actionSeconds', 'startupSeconds', 'settleSeconds', 'memoryLimitMB', 'memoryGrowthMBPerHour'].includes(name)) throw new Error(`未知 limits 配置：${name}`);
    if (value === null && name.startsWith('memory')) continue;
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || (value === 0 && name !== 'memoryGrowthMBPerHour')) throw new Error(`limits.${name} 必须为有效数值`);
    if (name === 'actionSeconds' && value > 15) throw new Error('操作超时不能大于 15 秒，以免超过准备步骤的 20 秒限制');
  }
  const seen = new Set();
  const games = input.games.map((target) => {
    if (!validId(target.gameId)) throw new Error('gameId 必须是实际的正整数 ID 或十进制字符串');
    if (seen.has(String(target.gameId))) throw new Error(`重复游戏 ID：${target.gameId}`);
    seen.add(String(target.gameId));
    if (!positive(target.playerCount) || target.playerCount > 6) throw new Error('playerCount 必须为 1–6 的整数');
    if (!Number.isSafeInteger(target.startLevelIndex) || target.startLevelIndex < 0) throw new Error('startLevelIndex 必须为从 0 开始的整数');
    return { ...target };
  });
  return { ...input, games, durationHours, hardwareMode, simulatedInputEnabled: input.simulatedInputEnabled ?? false };
}

// Sorting object keys avoids treating JSON property order as a content change.
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((k) => [k, stable(value[k])]));
  return value;
}

export function fingerprint(value) {
  return createHash('sha256').update(JSON.stringify(stable(value))).digest('hex');
}

export function effectiveDuration(entry) {
  const { summary, document } = entry;
  // RankRuntimeAssembler and RankType1Strategy always run the first configured level.
  const seconds = summary.type === 'rank'
    ? document.levels?.[0]?.durationSeconds
    : document.globalTimeLimit === true ? document.globalTimeLimitValue : null;
  if (!positive(seconds)) throw new Error(`游戏 ${summary.id} 没有可确认的有限整局时长`);
  return seconds;
}

export function buildPlan(input, catalog) {
  const config = normalizeConfig(input);
  const games = config.games.map((target) => {
    const entry = catalog.find((e) => String(e.summary.id) === String(target.gameId) && supported(e.summary));
    if (!entry) throw new Error(`游戏 ${target.gameId} 不存在或不可见，不进行同名替换`);
    const { summary, document } = entry;
    if (config.floor && (summary.width !== config.floor.width || summary.height !== config.floor.height
      || document.siteSizeWidth !== config.floor.width || document.siteSizeHeight !== config.floor.height)) throw new Error(`游戏 ${target.gameId} 尺寸与本次地砖尺寸不一致`);
    if (String(document.id) !== String(target.gameId)) throw new Error(`游戏 ${target.gameId} 详情 ID 不一致`);
    if (!positive(summary.minPlayers) || !positive(summary.maxPlayers)
      || target.playerCount < summary.minPlayers || target.playerCount > summary.maxPlayers) {
      throw new Error(`游戏 ${target.gameId} 人数不在支持范围内`);
    }
    if (!Array.isArray(document.levels) || target.startLevelIndex >= document.levels.length
      || (summary.type === 'rank' && target.startLevelIndex !== 0)) throw new Error(`游戏 ${target.gameId} 起始关卡无效`);
    return { ...target, name: summary.displayName || summary.name, type: summary.type,
      durationSeconds: effectiveDuration(entry), fingerprint: fingerprint(entry), snapshot: structuredClone(entry) };
  });
  const cycleUpperSeconds = games.reduce((sum, g) => sum + g.durationSeconds + 5 + 60, 0);
  if (config.durationHours * 3600 < cycleUpperSeconds) throw new Error(`时长不足以遍历一次列表，保守预算 ${cycleUpperSeconds} 秒（含配置与倒计时）`);
  return { config, games, cycleUpperSeconds, targetMillis: config.durationHours * 3600000 };
}

export function assertUnchanged(plan, catalog) {
  for (const expected of plan.games) {
    const actual = catalog.find((e) => String(e.summary.id) === String(expected.gameId) && supported(e.summary));
    if (!actual) throw new Error(`游戏 ${expected.gameId} 不存在或不可见`);
    if (fingerprint(actual) !== expected.fingerprint) throw new Error(`游戏 ${expected.gameId} 内容变化，停止本轮验收`);
  }
}

export async function readCatalog(baseUrl, fetcher = fetch, { includeUnsupported = false } = {}) {
  const base = new URL(baseUrl);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password) throw new Error('后端地址必须为无凭据的 HTTP 地址');
  async function get(path) {
    const response = await fetcher(new URL(path, base).href, { method: 'GET', signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error(`只读预检请求失败：${path}`);
    const body = await response.json();
    if (body.data === undefined || (body.code !== undefined && ![0, 200].includes(body.code))) throw new Error(`后端返回无效数据：${path}`);
    return body.data;
  }
  const list = await get('/games/playable');
  if (!Array.isArray(list)) throw new Error('可见游戏列表格式错误');
  const catalog = [];
  for (const rawSummary of list) {
    const summary = { ...rawSummary, id: rawSummary.gameId ?? rawSummary.id };
    if (!validId(summary.id)) throw new Error('后端返回非法游戏 ID');
    if (!supported(summary)) {
      if (includeUnsupported) catalog.push({ summary, document: null });
      continue;
    }
    const prefix = summary.type === 'rank' ? '/rank-game-editor/' : '/game-editor/';
    try {
      catalog.push({ summary, document: await get(`${prefix}${summary.id}`) });
    } catch (error) {
      if (!includeUnsupported) throw error;
      catalog.push({ summary, document: null, catalogError: error.message });
    }
  }
  return catalog;
}
