import fs from 'node:fs/promises';
import path from 'node:path';

export async function injectFromRun(directory, action, x, y, fetcher = fetch) {
  if (!['DOWN', 'UP'].includes(action) || ![x, y].every((value) => Number.isInteger(value) && value >= 0 && value < 256)) throw new Error('只能注入合法坐标的 DOWN/UP');
  const runtime = JSON.parse(await fs.readFile(path.join(directory, 'isolation.json'), 'utf8'));
  const config = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  if (config.simulatedInputEnabled !== true) throw new Error('本轮没有启用模拟输入');
  const base = `http://127.0.0.1:${runtime.backendPort}`;
  const request = async (url, options) => {
    const response = await fetcher(base + url, { signal: AbortSignal.timeout(5000), ...options });
    const body = await response.json();
    if (!response.ok || body.code !== 200) throw new Error(`注入请求失败：${body.message ?? response.status}`);
    return body.data;
  };
  const state = await request('/engine/game/state');
  if (state.engineState !== 'RUNNING' || !state.sessionId) throw new Error('当前没有 RUNNING 会话');
  const input = await request('/acceptance/soak/input');
  const sequence = input.sessionId === state.sessionId ? input.lastSequence + 1 : 1;
  return request('/acceptance/soak/input', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ runId: runtime.runId, sessionId: state.sessionId, sequence, action, x, y }) });
}
