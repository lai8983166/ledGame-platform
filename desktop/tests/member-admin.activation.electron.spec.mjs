import { test, expect, _electron as electron } from '@playwright/test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';

test('未激活桌面版先等待激活，输入可用且退出释放后端端口', async () => {
  test.setTimeout(90000);
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgame-activation-desktop-'));
  const port = await new Promise(resolve => { const server = net.createServer(); server.listen(0, '127.0.0.1', () => {
    const value = server.address().port; server.close(() => resolve(value));
  }); });
  const env = { ...process.env, LEDGAME_USER_DATA: userData, LEDGAME_PLATFORM_PORT: String(port),
    LEDGAME_DATABASE_BACKUP_ROOT: path.join(userData, 'isolated-backup'), LEDGAME_DATABASE_BACKUP_ENVIRONMENT: 'TEST' };
  delete env.ELECTRON_RUN_AS_NODE;
  let desktop;
  let originalClipboard;
  try {
    desktop = await electron.launch({ args: [path.resolve('desktop/member-admin/main.cjs')], env });
    const page = await desktop.firstWindow();
    await expect(page.locator('#activation')).toBeVisible({ timeout: 60000 });
    await expect(page.locator('#machine')).toHaveValue(/^LGM1-[0-9A-F]{64}$/);
    const health = await (await fetch(`http://127.0.0.1:${port}/api/health`)).json();
    expect(health.activated).toBe(false); expect(health.businessReady).toBe(false);
    expect((await fetch(`http://127.0.0.1:${port}/api/members`)).status).toBe(503);
    const status = await (await fetch(`http://127.0.0.1:${port}/api/system/startup-status`)).json();
    expect(status.state).toBe('CHECKING');
    expect(await fs.stat(path.join(userData, 'isolated-backup')).then(() => true).catch(() => false)).toBe(false);
    originalClipboard = await desktop.evaluate(({ clipboard }) => clipboard.readText());
    await page.locator('#copy-machine').click();
    expect(await desktop.evaluate(({ clipboard }) => clipboard.readText())).toBe(await page.locator('#machine').inputValue());
    await desktop.evaluate(({ clipboard }) => {
      globalThis.activationTestOriginalRead = clipboard.readText;
      clipboard.readText = () => { throw new Error('test clipboard failure'); };
    });
    try {
      await page.locator('#paste-code').click();
      await expect(page.locator('#activation-error')).toContainText('手动输入');
      await expect(page.locator('#activation-code')).toBeFocused();
      await page.locator('#activation-code').pressSequentially('manual-entry');
      await expect(page.locator('#activation-code')).toHaveValue('manual-entry');
      await page.locator('#activation-code').fill('');
    } finally {
      await desktop.evaluate(({ clipboard }) => {
        clipboard.readText = globalThis.activationTestOriginalRead;
        delete globalThis.activationTestOriginalRead;
      });
    }
    await desktop.evaluate(({ clipboard }) => clipboard.writeText(' \r\n'));
    await page.locator('#paste-code').click();
    await expect(page.locator('#activation-error')).toContainText('剪贴板');
    await expect(page.locator('#activation-code')).toBeFocused();
    await desktop.evaluate(({ clipboard }) => clipboard.writeText('manual-paste'));
    await page.locator('#activation-code').press('Control+V');
    await expect(page.locator('#activation-code')).toHaveValue('manual-paste');
    await page.locator('#activation-code').fill('');
    await desktop.evaluate(({ clipboard }) => clipboard.writeText(' LGACT1.fake.fake\r\n'));
    await page.locator('#paste-code').click();
    await expect(page.locator('#activation-code')).toHaveValue('LGACT1.fake.fake');
    expect(await fs.stat(path.join(userData, 'activation/license.json')).then(() => true).catch(() => false)).toBe(false);
    await page.locator('#activate').click();
    await expect(page.locator('#activation-error')).not.toHaveText('');
    await expect(page.locator('#activation-code')).toBeFocused();
    await page.locator('#activation-code').fill('');
    await page.locator('#activate').click();
    await expect(page.locator('#activation-error')).toContainText('请输入');
    await desktop.evaluate(({ clipboard }, text) => clipboard.writeText(text), originalClipboard);
    originalClipboard = undefined;
    const closed = desktop.waitForEvent('close');
    await page.locator('#exit').click();
    await closed; desktop = undefined;
    await expect.poll(async () => fetch(`http://127.0.0.1:${port}/api/health`).then(() => true).catch(() => false), { timeout: 10000 }).toBe(false);
  } finally {
    if (desktop) {
      if (originalClipboard !== undefined) await desktop.evaluate(({ clipboard }, text) => clipboard.writeText(text), originalClipboard).catch(() => {});
      await desktop.close();
    }
    await fs.rm(userData, {recursive: true, force: true});
  }
});
