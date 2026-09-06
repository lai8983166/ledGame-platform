const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const builderRequire = createRequire(require.resolve('electron-builder'));
const asar = createRequire(builderRequire.resolve('app-builder-lib'))('@electron/asar');
const root = path.resolve(__dirname, '../release/activation-signer/win-unpacked');
const archive = path.join(root, 'resources/app.asar');
const allowed = new Set(['package.json', 'scripts/activation-tool.mjs', 'desktop/activation-signer/main.cjs',
  'desktop/activation-signer/preload.cjs', 'desktop/activation-signer/index.html', 'desktop/activation-signer/renderer.js']);
let count = 0;
for (const entry of asar.listPackage(archive)) {
  const relative = entry.replace(/^[/\\]+/, '').replaceAll('\\', '/');
  const nativeEntry = relative.split('/').join(path.sep);
  const stat = asar.statFile(archive, nativeEntry);
  if (stat.files) continue;
  if (!allowed.has(relative) || stat.link || stat.unpacked) throw new Error(`签发工具包含意外文件：${relative}`);
  const content = asar.extractFile(archive, nativeEntry).toString('utf8');
  if (/(?:^|\n)-----BEGIN (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----/.test(content)) throw new Error(`发现私钥内容：${relative}`);
  count++;
}
if (count !== allowed.size) throw new Error('签发工具文件缺失');
if (fs.existsSync(path.join(root, 'resources/app.asar.unpacked'))) throw new Error('签发工具不应包含额外解包内容');
const resources = fs.readdirSync(path.join(root, 'resources'));
if (resources.some(file => !['app.asar', 'elevate.exe'].includes(file))) throw new Error('发现意外资源文件');
console.log(`签发工具包检查通过：${count} 个应用文件，无私钥或运行授权文件。`);
