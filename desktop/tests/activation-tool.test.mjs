import { describe, it, expect } from 'vitest';
import { generateKeyPairSync, verify } from 'node:crypto';
import { issueCode, getMachineProduct } from '../../scripts/activation-tool.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
const { assertActivationPublicKey, assertNoPrivateKeys } = createRequire(import.meta.url)('../../scripts/activation-release-check.cjs');

describe('厂家激活码工具', () => {
  it('发布检查拒绝缺少公钥、私钥和运行授权', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'activation-release-test-'));
    try {
      expect(() => assertActivationPublicKey(root)).toThrow('公钥');
      fs.writeFileSync(path.join(root, 'license.json'), '{}');
      expect(() => assertNoPrivateKeys(root)).toThrow('授权');
      fs.unlinkSync(path.join(root, 'license.json'));
      const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
      fs.writeFileSync(path.join(root, 'accidental.txt'), keys.privateKey);
      expect(() => assertNoPrivateKeys(root)).toThrow('私钥');
    } finally { fs.rmSync(root, {recursive:true, force:true}); }
  });
  it('生成可复制激活码，签名绑定完整载荷', () => {
    const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
    const code = issueCode(keys.privateKey, 'LGM1-' + 'A'.repeat(64), 'TEST');
    const [prefix, payload, signature] = code.split('.');
    expect(prefix).toBe('LGACT1');
    expect(verify(null, Buffer.from(payload, 'base64url'), keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(true);
    expect(JSON.parse(Buffer.from(payload, 'base64url'))).toMatchObject({ product: 'ledgame-member-admin', licenseId: 'TEST' });
    expect(() => issueCode(keys.privateKey, 'invalid')).toThrow('机器码');
  });
  it.each([['LGM1', 'ledgame-member-admin'], ['LGG1', 'ledgame-game']])('按 %s 机器码签发对应产品的永久授权', (prefix, product) => {
    const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
    const machineCode = prefix + '-' + 'ABCDEF0123456789'.repeat(4);
    expect(getMachineProduct(machineCode)).toBe(product);
    const [format, encoded, signature] = issueCode(keys.privateKey, machineCode, 'PRODUCT-TEST').split('.');
    const bytes = Buffer.from(encoded, 'base64url');
    const payload = JSON.parse(bytes);
    expect(format).toBe('LGACT1');
    expect(verify(null, bytes, keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(true);
    expect(payload).toEqual({ formatVersion: 1, product, machineCode, licenseId: 'PRODUCT-TEST', issuedAt: expect.any(String) });
    expect(Number.isFinite(Date.parse(payload.issuedAt))).toBe(true);
    const changed = Buffer.from(JSON.stringify({ ...payload, product: product === 'ledgame-game' ? 'ledgame-member-admin' : 'ledgame-game' }));
    expect(verify(null, changed, keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(false);
  });
  it.each(['LGM2-' + 'A'.repeat(64), 'LGG2-' + 'A'.repeat(64), 'LGG1-' + 'G'.repeat(64), 'LGG1-' + 'a'.repeat(64), 'LGG1-' + 'A'.repeat(63), null])('拒绝非法机器码 %s', machine => {
    expect(() => getMachineProduct(machine)).toThrow('机器码');
    expect(() => issueCode('not a private key', machine)).toThrow('机器码');
  });
  it('命令行保持原参数格式，同时按前缀签发两产品', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'activation-cli-test-'));
    const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
    const privatePath = path.join(directory, 'test-private.pem');
    try {
      fs.writeFileSync(privatePath, keys.privateKey);
      for (const [prefix, product] of [['LGM1', 'ledgame-member-admin'], ['LGG1', 'ledgame-game']]) {
        const machineCode = prefix + '-' + 'F'.repeat(64);
        const result = spawnSync(process.execPath, ['scripts/activation-tool.mjs', 'issue', privatePath, machineCode], { encoding: 'utf8' });
        expect(result.status).toBe(0); expect(result.stderr).toBe('');
        const [format, encoded, signature] = result.stdout.trim().split('.');
        expect(format).toBe('LGACT1');
        const bytes = Buffer.from(encoded, 'base64url');
        expect(JSON.parse(bytes)).toMatchObject({ product, machineCode });
        expect(verify(null, bytes, keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(true);
      }
      const invalid = spawnSync(process.execPath, ['scripts/activation-tool.mjs', 'issue', privatePath, 'LGG2-' + 'F'.repeat(64)], { encoding: 'utf8' });
      expect(invalid.status).toBe(1); expect(invalid.stdout).toBe(''); expect(invalid.stderr).toContain('机器码');
    } finally { fs.rmSync(directory, { recursive: true, force: true }); }
  });
  it('启动界面只提供粘贴激活，不使用文件选择或原生提示框', () => {
    const html = fs.readFileSync('desktop/member-admin/startup.html', 'utf8');
    expect(html).toContain('id="activation-code"');
    expect(html).toContain('id="paste-code"');
    expect(html).not.toMatch(/alert\(|confirm\(|type="file"/);
    const main = fs.readFileSync('desktop/member-admin/main.cjs', 'utf8');
    expect(main).toContain('backup.state !== "ACTIVATION_REQUIRED"');
    expect(main).toContain('body: JSON.stringify({ code })');
  });
});
