import { generateKeyPairSync, createPrivateKey, sign, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function issueCode(privateKey, machineCode, licenseId = randomUUID()) {
  if (!/^LGM1-[0-9A-F]{64}$/.test(machineCode)) throw new Error('机器码格式不正确，请复制完整机器码');
  const key = createPrivateKey(privateKey);
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('私钥必须是 Ed25519');
  const payload = Buffer.from(JSON.stringify({ formatVersion: 1, product: 'ledgame-member-admin', machineCode, licenseId, issuedAt: new Date().toISOString() }));
  return `LGACT1.${payload.toString('base64url')}.${sign(null, payload, key).toString('base64url')}`;
}

function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'keygen' && args.length === 1) {
    const directory = path.resolve(args[0]);
    const repo = path.resolve(import.meta.dirname, '..');
    const relative = path.relative(repo, directory);
    if (!relative || (relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative))) throw new Error('厂家密钥必须保存在仓库之外');
    const privatePath = path.join(directory, 'activation-private.pem'), publicPath = path.join(directory, 'activation-public.pem');
    if (fs.existsSync(privatePath) || fs.existsSync(publicPath)) throw new Error('密钥已存在，拒绝覆盖');
    const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(privatePath, keys.privateKey, { flag: 'wx', mode: 0o600 });
    fs.writeFileSync(publicPath, keys.publicKey, { flag: 'wx' });
    process.stdout.write(`密钥已保存到：${directory}\n请离线备份私钥，仅向构建提供公钥。\n`);
  } else if (command === 'issue' && args.length === 2) {
    process.stdout.write(issueCode(fs.readFileSync(path.resolve(args[0]), 'utf8'), args[1].trim()) + '\n');
  } else throw new Error('用法：node scripts/activation-tool.mjs keygen <仓库外目录> 或 issue <私钥路径> <机器码>');
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
