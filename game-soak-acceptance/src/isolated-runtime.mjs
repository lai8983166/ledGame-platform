import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import { createHash } from 'node:crypto';

export async function availablePort(requested = 0) {
  const server = net.createServer();
  return new Promise((resolve, reject) => {
    server.once('error', (error) => reject(new Error(`本机端口 ${requested} 不可用：${error.message}`)));
    server.listen(requested, '127.0.0.1', () => {
      const port = server.address().port;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

async function hashFile(file) {
  const handle = await fs.open(file, 'r');
  try {
    const hash = createHash('sha256');
    for await (const chunk of handle.createReadStream({ autoClose: false })) hash.update(chunk);
    return hash.digest('hex');
  } finally { await handle.close(); }
}

export async function prepareIsolatedRuntime(config, inheritedEnv = process.env) {
  if (!config.outputRoot || !config.gameExecutable) throw new Error('必须填写 outputRoot 和 gameExecutable');
  const executable = path.resolve(config.gameExecutable);
  if (!executable.toLowerCase().endsWith('.exe') || !(await fs.stat(executable)).isFile()) throw new Error('gameExecutable 必须指向打包 exe');
  if (!['real', 'simulated'].includes(config.hardwareMode)) throw new Error('必须明确选择 real 或 simulated');
  const { width, height } = config.floor ?? {};
  if (![width, height].every((n) => Number.isSafeInteger(n) && n >= 1 && n <= 256)) throw new Error('floor 尺寸必须为 1–256 的整数');
  const runId = config.runId || `soak-${Date.now()}`;
  if (!/^[a-zA-Z0-9_-]{1,80}$/.test(runId)) throw new Error('runId 只能包含字母、数字、横线或下划线');
  const outputRoot = path.resolve(config.outputRoot);
  const directory = path.join(outputRoot, runId);
  await fs.mkdir(outputRoot, { recursive: true });
  await fs.mkdir(directory); // Never reuse a previous run.
  const userData = path.join(directory, 'user-data');
  await fs.mkdir(path.join(userData, 'settings'), { recursive: true });
  await fs.writeFile(path.join(userData, 'settings', 'application.json'), JSON.stringify({ mode: 'game', entryMethod: 'touch' }));
  const resources = path.join(path.dirname(executable), 'resources');
  const source = config.gameDatabaseSource || path.join(resources, 'seed-database', 'runtime', 'ledgame.mv.db');
  const databaseDir = path.join(userData, 'database', 'runtime');
  await fs.mkdir(databaseDir, { recursive: true });
  const sourceHash = await hashFile(source);
  await fs.copyFile(source, path.join(databaseDir, 'ledgame.mv.db'));
  if (await hashFile(path.join(databaseDir, 'ledgame.mv.db')) !== sourceHash || await hashFile(source) !== sourceHash) {
    throw new Error('游戏库复制期间发生变化；请关闭使用该源库的应用后重试');
  }
  const backendPort = await availablePort(config.backendPort ?? 37680);
  const allocated = new Set([backendPort]);
  const allocate = async (requested = 0) => {
    if (requested && allocated.has(requested)) throw new Error(`配置端口重复：${requested}`);
    for (let attempt = 0; attempt < 20; attempt++) {
      const port = await availablePort(requested);
      if (!allocated.has(port)) { allocated.add(port); return port; }
    }
    throw new Error('无法分配互不冲突的本机端口');
  };
  const confDirectory = path.join(directory, 'elc408');
  await fs.mkdir(confDirectory);
  const inputs = [{ file: path.resolve(source), sha256: sourceHash }];
  const artifacts = [{ file: executable, sha256: await hashFile(executable) }];
  for (const file of [path.join(resources, 'app.asar'), ...await fs.readdir(path.join(resources, 'backend'))
    .then((names) => names.filter((name) => name.endsWith('.jar')).map((name) => path.join(resources, 'backend', name)))
    .catch((error) => { if (error.code === 'ENOENT') return []; throw error; })]) {
    try { artifacts.push({ file, sha256: await hashFile(file) }); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  let requestedFloorPort = 0;
  if (config.hardwareMode === 'real') {
    if (!config.floor.configDirectory) throw new Error('real 模式必须填写实际 conf/wiring 来源目录');
    for (const name of ['conf.json', 'wiring.json']) {
      const file = path.resolve(config.floor.configDirectory, name);
      inputs.push({ file, sha256: await hashFile(file) });
      await fs.copyFile(file, path.join(confDirectory, name));
      if (await hashFile(path.join(confDirectory, name)) !== inputs.at(-1).sha256) throw new Error(`配置复制期间发生变化：${name}`);
    }
    const conf = JSON.parse(await fs.readFile(path.join(confDirectory, 'conf.json'), 'utf8'));
    const wiring = JSON.parse(await fs.readFile(path.join(confDirectory, 'wiring.json'), 'utf8'));
    requestedFloorPort = conf.tcpServerPort;
    if (!Number.isInteger(requestedFloorPort) || requestedFloorPort < 1 || requestedFloorPort > 65535) throw new Error('conf.json 的 tcpServerPort 无效');
    if (wiring.width !== width || wiring.height !== height) throw new Error('floor 尺寸与 wiring.json 不一致');
  }
  const floorPort = await allocate(requestedFloorPort);
  const debugPort = await allocate();
  const simulated = config.hardwareMode === 'simulated';
  const spring = {
    server: { address: '127.0.0.1', port: backendPort },
    spring: { datasource: { url: `jdbc:h2:file:${databaseDir.replaceAll('\\', '/')}/ledgame;MODE=MySQL;DATABASE_TO_LOWER=TRUE` } },
    elc408: { enabled: !simulated, 'conf-path': path.join(confDirectory, 'conf.json'), 'wiring-path': path.join(confDirectory, 'wiring.json') },
    ledgame: { 'member-platform': { 'room-connection-enabled': false },
      soak: { 'input-enabled': config.simulatedInputEnabled === true, 'run-id': runId },
      acceptance: { 'fake-hardware-readiness-enabled': simulated } },
    led: { outputs: [
      { name: 'bridge', enabled: false, host: '127.0.0.1', port: 3001 },
      { name: 'debug-panel', enabled: true, host: '127.0.0.1', port: debugPort },
      { name: 'elc408-sdk', enabled: true, host: '127.0.0.1', port: floorPort, queueCapacity: 4, connectTimeoutMillis: 500, inputEnabled: true },
    ] },
  };
  const env = { ...inheritedEnv };
  // Inherited development/acceptance options must not change the requested mode.
  for (const key of Object.keys(env)) {
    if (/^(LED_|LEDGAME_|ELC408_|ACCEPTANCE_|SPRING_|VITE_|MEMBER_PLATFORM_|ELECTRON_RUN_AS_NODE|NODE_OPTIONS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)/.test(key)) delete env[key];
  }
  Object.assign(env, { LED_USER_DATA_DIR: userData, LED_PORTABLE_BACKEND_PORT: String(backendPort),
    LED_DEBUG_TCP_PORT: String(debugPort), LED_ROOM_CONNECTION_ENABLED: 'false',
    ELC408_ENABLED: String(!simulated), SPRING_APPLICATION_JSON: JSON.stringify(spring) });
  if (simulated) env.SPRING_PROFILES_ACTIVE = 'acceptance';
  const snapshot = { runId, directory, executable, inputs, artifacts, hardwareMode: config.hardwareMode,
    backendPort, debugPort, floorPort, width, height, presentation: 'game', entryMethod: 'touch', roomConnection: false };
  await fs.writeFile(path.join(directory, 'isolation.json'), JSON.stringify(snapshot, null, 2));
  return { ...snapshot, env, async verifySources() {
    for (const input of [...inputs, ...artifacts]) if (await hashFile(input.file) !== input.sha256) throw new Error(`源文件发生变化：${input.file}`);
  } };
}
