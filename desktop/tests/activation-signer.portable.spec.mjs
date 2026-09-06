import { test, expect, chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { generateKeyPairSync, verify } from 'node:crypto';

test('单文件便携 exe 自解压后可签发且重启保留私钥路径', async () => {
  test.setTimeout(90000);
  test.skip(!process.env.LEDGAME_SIGNER_PORTABLE_EXE, '需指定单文件 exe');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgame-signer-portable-'));
  const userData = path.join(root, 'user-data'); await fs.mkdir(userData);
  const keys = generateKeyPairSync('ed25519', { privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  const keyPath = path.join(root, 'test-private.txt'); await fs.writeFile(keyPath, keys.privateKey);
  await fs.writeFile(path.join(userData, 'signer-settings.json'), JSON.stringify({ keyPath }));
  const port = await new Promise(resolve => { const server = net.createServer(); server.listen(0, '127.0.0.1', () => {
    const port = server.address().port; server.close(() => resolve(port));
  }); });
  const env = { ...process.env, LEDGAME_SIGNER_USER_DATA: userData }; delete env.ELECTRON_RUN_AS_NODE;
  let child, browser;
  try {
    for (let run = 0; run < 2; run++) {
      child = spawn(path.resolve(process.env.LEDGAME_SIGNER_PORTABLE_EXE), [`--remote-debugging-port=${port}`], { env, stdio: 'ignore', windowsHide: true });
      let launchError; child.on('error', error => { launchError = error; });
      await expect.poll(async () => {
        if (launchError) throw launchError;
        return fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(1000) }).then(r => r.ok).catch(() => false);
      }, { timeout: 30000 }).toBe(true);
      browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
      const context = browser.contexts()[0];
      await expect.poll(() => context.pages().length).toBeGreaterThan(0);
      const page = context.pages()[0];
      await expect(page.locator('#key-path')).toHaveValue(keyPath);
      await page.locator('#machine').fill('LGM1-' + 'B'.repeat(64));
      await page.locator('#issue').click();
      await expect(page.locator('#result')).toHaveValue(/^LGACT1\./);
      await expect(page.locator('#copy')).toBeInViewport();
      const [, payload, signature] = (await page.locator('#result').inputValue()).split('.');
      expect(verify(null, Buffer.from(payload, 'base64url'), keys.publicKey, Buffer.from(signature, 'base64url'))).toBe(true);
      await page.screenshot({ path: test.info().outputPath(`portable-${run}.png`) });
      await page.close(); await browser.close(); browser = undefined;
      await expect.poll(() => child.exitCode, { timeout: 15000 }).not.toBe(null);
      expect(child.exitCode).toBe(0); child = undefined;
    }
  } finally {
    await browser?.close().catch(() => {});
    if (child?.pid && child.exitCode === null) await new Promise(resolve => {
      const killer = spawn('taskkill', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true, stdio: 'ignore' });
      killer.once('error', resolve); killer.once('exit', resolve);
    });
    await fs.rm(root, { recursive: true, force: true });
  }
});
