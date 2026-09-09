import fs from 'node:fs/promises';
import path from 'node:path';

const MAX_DIMENSION = 256;

function positiveDimension(value) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 1 && number <= MAX_DIMENSION ? number : null;
}

function dimensionsFromObject(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const width = positiveDimension(value.width ?? value.siteSizeWidth ?? value.gridWidth ?? value.grid?.width);
  const height = positiveDimension(value.height ?? value.siteSizeHeight ?? value.gridHeight ?? value.grid?.height);
  return width && height ? { width, height } : null;
}

function dimensionsFromPoints(value, state = { maxX: -1, maxY: -1 }) {
  if (Array.isArray(value)) {
    if (value.length >= 2 && Number.isInteger(value[0]) && Number.isInteger(value[1])) {
      state.maxX = Math.max(state.maxX, value[0]);
      state.maxY = Math.max(state.maxY, value[1]);
      return state;
    }
    for (const child of value) dimensionsFromPoints(child, state);
    return state;
  }
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) dimensionsFromPoints(child, state);
  }
  return state;
}

export function inferWiringDimensions(wiring) {
  const direct = dimensionsFromObject(wiring);
  if (direct) return direct;
  const points = dimensionsFromPoints(wiring?.lines ?? wiring);
  const width = positiveDimension(points.maxX + 1);
  const height = positiveDimension(points.maxY + 1);
  return width && height ? { width, height } : null;
}

export function defaultHardwareConfigDirectory(gameExecutable) {
  if (!gameExecutable || !String(gameExecutable).trim()) throw new Error('必须填写游戏端 exe 路径');
  return path.join(path.dirname(path.resolve(String(gameExecutable).trim())), 'elc408');
}

export async function discoverRealHardwareConfig(gameExecutable) {
  const directory = defaultHardwareConfigDirectory(gameExecutable);
  const files = {};
  for (const name of ['conf.json', 'wiring.json']) {
    const file = path.join(directory, name);
    let source;
    try {
      source = await fs.readFile(file, 'utf8');
    } catch (error) {
      throw new Error(`未找到真实地砖配置：${file}`);
    }
    try {
      files[name] = JSON.parse(source);
    } catch (error) {
      throw new Error(`${file} 不是有效的 JSON`);
    }
  }
  const floor = inferWiringDimensions(files['wiring.json']);
  if (!floor) throw new Error(`${path.join(directory, 'wiring.json')} 未包含可推断的地砖尺寸`);
  return { configDirectory: directory, floor, conf: files['conf.json'], wiring: files['wiring.json'] };
}
