import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { availablePort, prepareIsolatedRuntime } from '../src/isolated-runtime.mjs';

test('isolation copies source, overrides inherited dev settings, never reuses run', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-isolation-'));
  try {
    const exe = path.join(root, 'game.exe'); const db = path.join(root, 'source.mv.db');
    await fs.writeFile(exe, 'fixture only'); await fs.writeFile(db, 'source fixture');
    const config = { gameExecutable: exe, gameDatabaseSource: db, outputRoot: path.join(root, 'runs'),
      runId: 'test', hardwareMode: 'simulated', backendPort: 0, floor: { width: 8, height: 8 } };
    const runtime = await prepareIsolatedRuntime(config, { LED_BACKEND_URL: 'http://wrong', NODE_OPTIONS: '--inspect', SPRING_PROFILES_ACTIVE: 'wrong' });
    assert.equal(runtime.env.LED_BACKEND_URL, undefined);
    assert.equal(runtime.env.NODE_OPTIONS, undefined);
    assert.equal(runtime.env.SPRING_PROFILES_ACTIVE, 'acceptance');
    const spring = JSON.parse(runtime.env.SPRING_APPLICATION_JSON);
    assert.equal(spring.ledgame['member-platform']['room-connection-enabled'], false);
    assert.equal(spring.elc408.enabled, false);
    await fs.writeFile(path.join(runtime.directory, 'user-data/database/runtime/ledgame.mv.db'), 'modified copy');
    await runtime.verifySources();
    await assert.rejects(() => prepareIsolatedRuntime(config), /EEXIST/);
    await assert.rejects(() => prepareIsolatedRuntime({ ...config, runId: '../escape' }), /runId/);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('occupied port is an explicit error, never silently selects another', async () => {
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try { await assert.rejects(() => availablePort(server.address().port), /端口.*不可用/); }
  finally { await new Promise((resolve) => server.close(resolve)); }
});

test('real mode uses existing config TCP port, checks dimensions and never enables fake readiness', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'game-soak-real-config-'));
  try {
    const exe = path.join(root, 'game.exe'); const db = path.join(root, 'source.mv.db');
    await fs.writeFile(exe, 'fixture'); await fs.writeFile(db, 'fixture');
    const port = await availablePort();
    await fs.writeFile(path.join(root, 'conf.json'), JSON.stringify({ tcpServerPort: port }));
    await fs.writeFile(path.join(root, 'wiring.json'), JSON.stringify({ width: 8, height: 8 }));
    const config = { gameExecutable: exe, gameDatabaseSource: db, outputRoot: path.join(root, 'runs'), runId: 'real-config',
      hardwareMode: 'real', backendPort: 0, floor: { width: 8, height: 8, configDirectory: root } };
    const runtime = await prepareIsolatedRuntime(config, { SPRING_PROFILES_ACTIVE: 'acceptance', ELC408_ENABLED: 'false' });
    assert.equal(runtime.floorPort, port);
    assert.equal(new Set([runtime.floorPort, runtime.backendPort, runtime.debugPort]).size, 3);
    assert.equal(runtime.env.SPRING_PROFILES_ACTIVE, undefined);
    const spring = JSON.parse(runtime.env.SPRING_APPLICATION_JSON);
    assert.equal(spring.elc408.enabled, true);
    assert.equal(spring.ledgame.acceptance['fake-hardware-readiness-enabled'], false);
    await runtime.verifySources();
    await assert.rejects(() => prepareIsolatedRuntime({ ...config, runId: 'mismatch', floor: { ...config.floor, width: 16 } }), /尺寸/);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
