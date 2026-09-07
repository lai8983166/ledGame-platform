import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';

if (process.platform !== 'win32') throw new Error('此脚本生成 Windows 便携工具，请在 Windows 构建');
const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const releaseRoot = path.join(root, 'release');
const outputIndex = process.argv.indexOf('--output');
const requestedOutput = outputIndex >= 0 ? process.argv[outputIndex + 1] : '';
if (outputIndex >= 0 && !requestedOutput) throw new Error('--output 后必须提供目录');
const finalOutput = requestedOutput
  ? path.resolve(root, requestedOutput)
  // 单独构建仍使用唯一目录，避免覆盖操作者放在旧工具旁的配置或结果。
  : path.join(releaseRoot, `game-soak-tool-${Date.now()}`);
const relativeOutput = path.relative(releaseRoot, finalOutput);
if (!relativeOutput || relativeOutput.startsWith('..') || path.isAbsolute(relativeOutput)) {
  throw new Error(`烤机工具输出目录必须位于 release 的子目录：${finalOutput}`);
}
const replaceFixedOutput = Boolean(requestedOutput);
const output = replaceFixedOutput
  ? path.join(releaseRoot, `.game-soak-build-${process.pid}-${Date.now()}`)
  : finalOutput;
await fs.mkdir(path.join(output, 'runtime'), { recursive: true });
await fs.copyFile(process.execPath, path.join(output, 'runtime', 'node.exe'));
await fs.cp(path.join(root, 'game-soak-acceptance', 'src'), path.join(output, 'app'), { recursive: true });
for (const name of ['playwright', 'playwright-core']) {
  const dependencyRequire = name === 'playwright-core' ? createRequire(require.resolve('playwright/package.json')) : require;
  const source = path.dirname(dependencyRequire.resolve(`${name}/package.json`));
  await fs.cp(source, path.join(output, 'node_modules', name), { recursive: true });
}
for (const file of ['config.example.json', '使用说明.md']) {
  await fs.copyFile(path.join(root, 'game-soak-acceptance', file), path.join(output, file));
}
await fs.writeFile(path.join(output, 'soak.cmd'), '@echo off\r\n"%~dp0runtime\\node.exe" "%~dp0app\\cli.mjs" %*\r\nexit /b %errorlevel%\r\n');
await fs.writeFile(path.join(output, 'runtime', 'NOTICE.txt'), `Node.js ${process.version}; copyright Node.js contributors; MIT license.\r\nFull runtime license: https://github.com/nodejs/node/blob/${process.version}/LICENSE\r\nPlaywright license/notices are included in node_modules.\r\n`);
async function verifyContents(directory) {
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    if (entry.isSymbolicLink() || /\.(db|sqlite|sqlite3)$/i.test(entry.name)
      || /private.*\.(pem|key|txt)$/i.test(entry.name)
      || ['seed-database', 'user-data', 'activation', 'jre', 'tests'].includes(entry.name)) throw new Error(`便携包出现非交付内容：${entry.name}`);
    if (entry.isDirectory()) await verifyContents(path.join(directory, entry.name));
  }
}
await verifyContents(output);
const template = JSON.parse(await fs.readFile(path.join(output, 'config.example.json'), 'utf8'));
if (template.games.length || template.gameExecutable || template.gameDatabaseSource) throw new Error('交付模板不得包含实际业务库或本机游戏目标');
const check = spawnSync(path.join(output, 'runtime', 'node.exe'), ['--input-type=module', '-e', "await import('./app/run.mjs'); console.log('便携依赖检查通过')"], { cwd: output, encoding: 'utf8', windowsHide: true });
if (check.status !== 0) throw new Error(check.stderr || '便携工具依赖检查失败');
await fs.writeFile(path.join(output, '.ledgame-build-output'), 'game-soak-portable\n', 'utf8');
if (replaceFixedOutput) {
  const marker = path.join(finalOutput, '.ledgame-build-output');
  const targetExists = await fs.stat(finalOutput).then(() => true).catch(() => false);
  if (targetExists && !await fs.stat(marker).then(() => true).catch(() => false)) {
    await fs.rm(output, { recursive: true, force: true });
    throw new Error(`拒绝覆盖不是由本脚本生成的目录：${finalOutput}`);
  }
  if (targetExists) await fs.rm(finalOutput, { recursive: true, force: true });
  await fs.rename(output, finalOutput);
}
console.log(check.stdout.trim());
console.log(`已生成：${finalOutput}`);
