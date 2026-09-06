import { describe, it, expect } from 'vitest';
import { generateKeyPairSync, verify } from 'node:crypto';
import { issueCode } from '../../scripts/activation-tool.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
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
