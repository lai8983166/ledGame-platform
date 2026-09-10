import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeConfig, buildPlan, assertUnchanged, readCatalog } from '../src/preflight.mjs';

// Deliberately local fixtures, never written into an application database.
const config = (games = [{ gameId: 41, playerCount: 2, startLevelIndex: 0 }]) => ({ games });
const entry = () => ({
  summary: { id: 41, name: 'simple', type: 'default', minPlayers: 1, maxPlayers: 6 },
  document: { id: 41, globalTimeLimit: true, globalTimeLimitValue: 60, levels: [{ label: '一' }] },
});

test('default is eighteen hours; empty and duplicate lists fail', () => {
  const normalized = normalizeConfig(config());
  assert.equal(normalized.durationHours, 18);
  assert.equal(normalized.limits.memoryLimitMB, 8192);
  assert.equal(normalized.limits.memoryGrowthMBPerHour, null);
  assert.throws(() => normalizeConfig(config([])), /不能为空/);
  assert.throws(() => normalizeConfig(config([config().games[0], config().games[0]])), /重复/);
  for (const durationHours of [0, -1, '18', Infinity]) {
    assert.throws(() => normalizeConfig({ ...config(), durationHours }), /durationHours/);
  }
});

test('missing or invisible IDs fail, never substitute by name', () => {
  assert.throws(() => buildPlan(config(), []), /41.*不存在或不可见/);
  const other = entry(); other.summary.id = 42; other.document.id = 42;
  assert.throws(() => buildPlan(config(), [other]), /41.*不存在或不可见/);
});

test('invalid player counts and start levels fail without rounding', () => {
  for (const playerCount of [0, 7, 1.2, '2']) {
    assert.throws(() => buildPlan(config([{ gameId: 41, playerCount, startLevelIndex: 0 }]), [entry()]));
  }
  for (const startLevelIndex of [-1, 1, 0.2, '0']) {
    assert.throws(() => buildPlan(config([{ gameId: 41, playerCount: 2, startLevelIndex }]), [entry()]));
  }
});

test('unknown or infinite effective duration fails, per-level timer is not global', () => {
  for (const globalTimeLimitValue of [null, 0, -2, '60']) {
    const e = entry(); e.document.globalTimeLimitValue = globalTimeLimitValue;
    assert.throws(() => buildPlan(config(), [e]), /有限/);
  }
  const e = entry(); e.document.globalTimeLimit = false;
  e.document.levels[0].option = { timeLimit: true, timeLimitValue: 60 };
  assert.throws(() => buildPlan(config(), [e]), /有限/);
});

test('Rank uses actual first-level duration even without global toggle', () => {
  const e = entry(); e.summary.type = 'rank'; e.summary.name = 'rank-type1';
  e.document.globalTimeLimit = false; e.document.levels = [{ durationSeconds: 90 }];
  assert.equal(buildPlan(config(), [e]).games[0].durationSeconds, 90);
});

test('Jackson string IDs match numeric config without rounding large IDs', () => {
  const e = entry(); e.summary.id = '41'; e.document.id = '41';
  assert.equal(buildPlan(config(), [e]).games[0].durationSeconds, 60);
  assert.throws(() => normalizeConfig(config([{ ...config().games[0] }, { ...config().games[0], gameId: '41' }])), /重复/);
  const id = '9007199254740993'; e.summary.id = id; e.document.id = id;
  assert.equal(buildPlan(config([{ ...config().games[0], gameId: id }]), [e]).games[0].gameId, id);
  assert.throws(() => normalizeConfig(config([{ ...config().games[0], gameId: Number(id) }])), /gameId/);
});

test('preflight is read-only and content or visibility changes stop the run', () => {
  const e = entry(); const before = JSON.stringify(e);
  const plan = buildPlan(config(), [e]);
  assert.equal(JSON.stringify(e), before);
  assert.doesNotThrow(() => assertUnchanged(plan, [e]));
  const changed = structuredClone(e); changed.document.levels[0].label = '改变';
  assert.throws(() => assertUnchanged(plan, [changed]), /内容变化/);
  assert.throws(() => assertUnchanged(plan, []), /不存在或不可见/);
});

test('too-short duration cannot pretend to cover the whole list', () => {
  assert.throws(() => buildPlan({ ...config(), durationHours: 0.001 }, [entry()]), /遍历/);
});

test('floor dimensions and predeclared limits are checked instead of silently coerced', () => {
  const e = entry();
  Object.assign(e.summary, { width: 8, height: 8 });
  Object.assign(e.document, { siteSizeWidth: 8, siteSizeHeight: 8 });
  assert.doesNotThrow(() => buildPlan({ ...config(), floor: { width: 8, height: 8 } }, [e]));
  assert.throws(() => buildPlan({ ...config(), floor: { width: 16, height: 36 } }, [e]), /尺寸/);
  for (const limits of [{ memoryLimitMB: '4000' }, { actionSeconds: 20 }, { settleSeconds: -1 }, { unexpected: 1 }]) {
    assert.throws(() => normalizeConfig({ ...config(), limits }));
  }
});

test('catalog reader only GETs and never calls seed or start endpoints', async () => {
  const calls = [];
  const catalog = await readCatalog('http://127.0.0.1:37680', async (url, options) => {
    calls.push([new URL(url).pathname, options.method]);
    const summary = { ...entry().summary, gameId: '41' }; delete summary.id;
    return { ok: true, json: async () => ({ code: 200, data: url.endsWith('/playable') ? [summary] : entry().document }) };
  });
  assert.equal(catalog.length, 1);
  assert.deepEqual(calls, [['/games/playable', 'GET'], ['/game-editor/41', 'GET']]);
});

test('wizard catalog mode keeps unsupported games visible for an explicit disabled reason', async () => {
  const calls = [];
  const catalog = await readCatalog('http://127.0.0.1:37680', async (url, options) => {
    calls.push([new URL(url).pathname, options.method]);
    const summary = { id: 41, name: 'simple', type: 'default', minPlayers: 1, maxPlayers: 4 };
    const unsupported = { id: 99, name: 'future', type: 'experimental', minPlayers: 1, maxPlayers: 4 };
    return { ok: true, json: async () => ({ code: 200, data: url.endsWith('/playable') ? [summary, unsupported] : entry().document }) };
  }, { includeUnsupported: true });
  assert.equal(catalog.length, 2);
  assert.equal(catalog[1].summary.id, 99);
  assert.equal(catalog[1].document, null);
  assert.deepEqual(calls, [['/games/playable', 'GET'], ['/game-editor/41', 'GET']]);
});
