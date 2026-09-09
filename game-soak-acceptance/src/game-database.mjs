import fs from 'node:fs/promises';
import path from 'node:path';

const DATABASE_FILE = 'ledgame.mv.db';
const APP_DATA_DIRECTORY_NAMES = ['led-game', 'LED Game'];

function uniquePaths(values) {
  const seen = new Set();
  return values.filter((value) => {
    if (!value) return false;
    const normalized = path.resolve(value);
    if (seen.has(normalized.toLowerCase())) return false;
    seen.add(normalized.toLowerCase());
    return true;
  }).map((value) => path.resolve(value));
}

function databasePath(root) {
  return root ? path.join(root, 'database', 'runtime', DATABASE_FILE) : '';
}

export function localDatabaseCandidates(gameExecutable, {
  appData = process.env.APPDATA,
  localAppData = process.env.LOCALAPPDATA,
} = {}) {
  if (!gameExecutable || !String(gameExecutable).trim()) throw new Error('必须填写游戏端 exe 路径');
  const executableDirectory = path.dirname(path.resolve(String(gameExecutable).trim()));
  const portableRoot = path.join(executableDirectory, 'user-data');
  return uniquePaths([
    databasePath(portableRoot),
    ...APP_DATA_DIRECTORY_NAMES.flatMap((name) => [
      databasePath(appData ? path.join(appData, name) : ''),
      databasePath(localAppData ? path.join(localAppData, name) : ''),
    ]),
  ]);
}

async function regularFile(file) {
  try {
    const stat = await fs.stat(file);
    return stat.isFile() && stat.size > 0 ? stat : null;
  } catch {
    return null;
  }
}

function lockFileCandidates(source) {
  return [
    source.replace(/\.mv\.db$/i, '.lock.db'),
    source.replace(/\.mv\.db$/i, '.trace.db'),
  ];
}

/**
 * Locate the user's current game database without opening or modifying it.
 * The caller copies the returned file into an isolated run directory.
 */
export async function discoverGameDatabaseSource(gameExecutable, options = {}) {
  const candidates = localDatabaseCandidates(gameExecutable, options);
  const existing = [];
  for (const candidate of candidates) {
    const stat = await regularFile(candidate);
    if (stat) existing.push({ path: candidate, modifiedAt: stat.mtimeMs, bytes: stat.size });
  }
  if (!existing.length) return null;

  // Prefer the most recently changed copy when both a portable and installed
  // user-data directory exist. This matches the database the operator just edited.
  existing.sort((left, right) => right.modifiedAt - left.modifiedAt);
  const selected = existing[0];
  for (const lock of lockFileCandidates(selected.path)) {
    if (await regularFile(lock)) {
      throw new Error(`本机游戏数据库正在使用，请先关闭游戏端后重试：${selected.path}`);
    }
  }
  return { ...selected, candidates: existing };
}
