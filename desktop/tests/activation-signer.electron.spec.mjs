import { test, expect, _electron as electron } from '@playwright/test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { generateKeyPairSync, verify } from 'node:crypto';

test('厂家签发工具：选择/取消、签发复制、路径记忆和私钥丢失提示', async () => {
  test.setTimeout(60000);
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgame-signer-'));
  const keyPath = path.join(directory, 'test-private.txt');
  const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  await fs.writeFile(keyPath, keys.privateKey);
  const badPath = path.join(directory, 'invalid.txt'); await fs.writeFile(badPath, 'invalid');
  const userData = path.join(directory, 'user-data');
  const env = { ...process.env, LEDGAME_SIGNER_USER_DATA: userData };
  delete env.ELECTRON_RUN_AS_NODE;
  const launch = () => electron.launch(process.env.LEDGAME_SIGNER_EXE
    ? { executablePath: path.resolve(process.env.LEDGAME_SIGNER_EXE), args: [], env }
    : { args: [path.resolve('desktop/activation-signer/main.cjs')], env });
  let desktop;
  let originalClipboard;
  const select = async file => {
    await desktop.evaluate(({ dialog }, selected) => {
      dialog.showOpenDialog = async () => ({ canceled: !selected, filePaths: selected ? [selected] : [] });
    }, file);
  };
  try {
    desktop = await launch(); let page = await desktop.firstWindow();
    originalClipboard = await desktop.evaluate(({ clipboard }) => clipboard.readText());
    await expect(page.locator('#choose')).toBeEnabled();
    await page.locator('#issue').click(); await expect(page.locator('#status')).toContainText('完整客户机器码');
    await select(null); await page.locator('#choose').click();
    await expect(page.locator('#machine')).toBeFocused();
    await page.locator('#machine').pressSequentially('test'); await expect(page.locator('#machine')).toHaveValue('test');
    await select(badPath); await page.locator('#choose').click();
    await expect(page.locator('#status')).toContainText('有效的 Ed25519 私钥');
    await expect(page.locator('#machine')).toBeFocused();
    await select(keyPath); await page.locator('#choose').click();
    await expect(page.locator('#key-path')).toHaveValue(keyPath);
    const machine = 'LGM1-' + 'A'.repeat(64);
    await page.locator('#machine').fill(' \r\n' + machine + '\t');
    await page.locator('#issue').click();
    await expect(page.locator('#result')).toHaveValue(/^LGACT1\./);
    const code = await page.locator('#result').inputValue();
    const [, payload, signature] = code.split('.');
    expect(verify(null, Buffer.from(payload, 'base64url'), keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(true);
    expect(JSON.parse(Buffer.from(payload, 'base64url'))).toMatchObject({ machineCode: machine, product: 'ledgame-member-admin' });
    await page.locator('#copy').click(); await expect(page.locator('#status')).toContainText('已复制');
    expect(await desktop.evaluate(({ clipboard }) => clipboard.readText())).toBe(code);
    await page.screenshot({ path: test.info().outputPath('signer.png') });
    await page.locator('#machine').fill('invalid'); await expect(page.locator('#copy')).toBeDisabled();
    await expect(page.locator('#result')).toHaveValue('');
    const settings = await fs.readFile(path.join(userData, 'signer-settings.json'), 'utf8');
    expect(JSON.parse(settings)).toEqual({ keyPath }); expect(settings).not.toContain(keys.privateKey);
    await desktop.evaluate(({ clipboard }, text) => clipboard.writeText(text), originalClipboard); originalClipboard = undefined;
    await desktop.close(); desktop = await launch(); page = await desktop.firstWindow();
    await expect(page.locator('#key-path')).toHaveValue(keyPath);
    await fs.unlink(keyPath);
    await page.locator('#machine').fill(machine); await page.locator('#issue').click();
    await expect(page.locator('#status')).toContainText('重新选择厂家私钥');
    await expect(page.locator('#result')).toHaveValue('');
    await expect(page.locator('#machine')).toBeFocused();
  } finally {
    if (desktop) {
      if (originalClipboard !== undefined) await desktop.evaluate(({ clipboard }, text) => clipboard.writeText(text), originalClipboard).catch(() => {});
      await desktop.close();
    }
    await fs.rm(directory, { recursive: true, force: true });
  }
});
