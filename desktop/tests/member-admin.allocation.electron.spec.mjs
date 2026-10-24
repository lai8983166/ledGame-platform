import { test, expect, _electron as electron } from '@playwright/test';
import fs from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { prepareActivationLicense } from './activation-fixture.mjs';

const root = path.resolve(import.meta.dirname, '../..');
test('打包实操：账户充值、连续预充值、柜台及自助领取、还卡退回', async () => {
  test.setTimeout(180_000);
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgame-allocation-'));
  const port = await new Promise(resolve => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
  });
  const env = { ...process.env, LEDGAME_USER_DATA: path.join(directory, 'admin'), LEDGAME_PLATFORM_PORT: String(port),
    LEDGAME_DATABASE_BACKUP_ENABLED: 'false', LEDGAME_DATABASE_BACKUP_ENVIRONMENT: 'TEST',
    PLATFORM_FACTORY_ADMIN_USERNAME: 'allocation-admin', PLATFORM_FACTORY_ADMIN_PASSWORD: 'allocation-password' };
  delete env.ELECTRON_RUN_AS_NODE;
  let admin, kiosk;
  try {
    await prepareActivationLicense(env.LEDGAME_USER_DATA);
    const executablePath = path.join(root, 'release/member-admin/win-unpacked/LED Game 会员管理端.exe');
    admin = await electron.launch({ executablePath, cwd: path.dirname(executablePath), env });
    let page;
    await expect.poll(async () => {
      for (const window of admin.windows()) {
        try { if (await window.getByTestId('operator-login-page').count()) { page = window; return true; } } catch {}
      }
      return false;
    }, { timeout: 60_000 }).toBe(true);
    await page.evaluate(() => localStorage.setItem('ledgame.member-admin.locale', 'zh-CN'));
    await page.reload();
    await page.getByTestId('operator-login-username').fill('allocation-admin');
    await page.getByTestId('operator-login-password').fill('allocation-password');
    await page.getByTestId('operator-login-submit').click();
    await expect(page.getByTestId('admin-nav-members')).toBeVisible();
    const login = await fetch(`http://127.0.0.1:${port}/api/operator-auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'allocation-admin', password: 'allocation-password' }),
    }).then(response => response.json());
    const api = async (route, method='GET', body) => {
      const response = await fetch(`http://127.0.0.1:${port}${route}`, { method,
        headers: { 'Content-Type': 'application/json', 'X-Operator-Id': String(login.id) },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      expect(response.ok, `${route}: ${response.status}`).toBe(true);
      return response.json();
    };
    await page.getByTestId('admin-nav-members').click();
    await page.getByTestId('admin-member-create').click();
    await page.getByTestId('member-create-name').fill('桌面分配测试');
    await page.getByTestId('member-create-phone').fill('13800138880');
    await page.getByTestId('member-recharge-minutes').fill('100');
    await page.getByTestId('member-recharge-amount').fill('50');
    await page.getByTestId('member-recharge-submit').click();
    await expect(page.getByTestId('member-recharge-dialog')).toHaveCount(0);
    const member = (await api('/api/members')).find(member => member.phone==='13800138880');
    const row = page.getByTestId(`admin-member-${member.id}`);
    await expect(row.getByTestId('admin-member-balance')).toContainText('100 分');
    await row.getByTestId('admin-member-recharge').click();
    await page.getByTestId('member-recharge-minutes').fill('10');
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
    expect((await api('/api/members'))[0].remainingSeconds).toBe(6000);
    await row.getByTestId('admin-member-recharge').click();
    await page.getByTestId('member-recharge-minutes').fill('10');
    await page.getByTestId('member-recharge-submit').click();
    await expect(row.getByTestId('admin-member-balance')).toContainText('110 分');
    await row.getByTestId('admin-member-issue').click();
    await expect(page.getByTestId('card-member-phone')).toHaveCount(0);
    await expect(page.getByTestId('card-scan-prompt')).toBeVisible();
    await page.keyboard.type('2283055880'); await page.keyboard.press('Enter');
    await page.getByTestId('card-minutes').fill('60');
    await page.getByTestId('card-submit').click();
    await expect(row.getByTestId('admin-member-balance')).toContainText('50 分');
    await page.screenshot({ path: test.info().outputPath('会员列表-充值与部分分配.png'), fullPage: true });
    const band = await api('/api/wristbands/2283055880');
    expect(band.status).toBe('ACTIVE');
    // Compensation changes only the unallocated account, never the running wristband.
    await row.getByTestId('admin-member-adjust').click();
    await page.getByTestId('member-adjustment-minutes').fill('30');
    await page.getByTestId('member-adjustment-submit').click();
    await expect(page.getByTestId('member-adjustment-error')).toContainText('请填写调整原因');
    await page.getByTestId('member-adjustment-reason').fill('取消调整测试');
    await page.getByTestId('member-adjustment-cancel').click();
    expect((await api('/api/members'))[0].remainingSeconds).toBe(3000);
    await row.getByTestId('admin-member-adjust').click();
    await page.getByTestId('member-adjustment-minutes').fill('30');
    await page.getByTestId('member-adjustment-reason').fill('误扣补偿测试');
    await expect(page.getByTestId('member-adjustment-expected')).toContainText('80');
    await page.getByTestId('member-adjustment-submit').click();
    await expect(row.getByTestId('admin-member-balance')).toContainText('80 分');
    await row.getByTestId('admin-member-adjust').click();
    await page.getByTestId('member-adjustment-direction').selectOption('DEDUCT');
    await page.getByTestId('member-adjustment-minutes').fill('90');
    await page.getByTestId('member-adjustment-reason').fill('补偿撤销测试');
    await page.getByTestId('member-adjustment-submit').click();
    await expect(page.getByTestId('member-adjustment-error')).toContainText('余额不足');
    await page.getByTestId('member-adjustment-minutes').fill('30');
    await page.getByTestId('member-adjustment-submit').click();
    await expect(row.getByTestId('admin-member-balance')).toContainText('50 分');
    expect((await api('/api/wristbands/2283055880')).startedAt).toBe(band.startedAt);
    expect((await api('/api/wristbands/2283055880')).allocatedSeconds).toBe(band.allocatedSeconds);
    expect((await api('/api/dashboard/overview')).revenueTodayCents).toBe(5000);
    await page.setViewportSize({width:1280,height:800});
    await row.locator('.member-cell').click();
    await page.getByTestId('admin-member-adjustment-history-button').click();
    const adjustments = page.getByTestId('admin-member-adjustment-history-modal');
    await expect(adjustments).toContainText('误扣补偿测试');
    await expect(adjustments).toContainText('补偿撤销测试');
    await expect(adjustments).toContainText('allocation-admin');
    await page.screenshot({path:test.info().outputPath('人工时长调整-记录与操作员.png'),fullPage:true,animations:'disabled'});
    await page.getByRole('dialog',{name:'调整记录',exact:true}).getByRole('button',{name:'关闭弹窗'}).click();
    await expect(adjustments).toHaveCount(0);
    await page.getByRole('button',{name:'关闭详情'}).click();
    await expect.poll(async () => (await api('/api/wristbands/2283055880')).remainingSeconds).toBeLessThan(3600);
    await page.getByTestId('admin-nav-rooms').click();
    await page.getByTestId('admin-card-return').click();
    await page.keyboard.type('2283055880'); await page.keyboard.press('Enter');
    await expect(page.getByTestId('card-confirmation')).toBeVisible();
    await page.getByTestId('card-submit').click();
    await expect(page.getByTestId('card-confirmation')).toHaveCount(0);
    const afterReturn = (await api('/api/members'))[0].remainingSeconds;
    expect(afterReturn).toBeLessThan(6600); expect(afterReturn).toBeGreaterThan(6500);
    await api('/api/wristbands/return', 'POST', { uid:'2283055880', bindingId:band.bindingId });
    expect((await api('/api/members'))[0].remainingSeconds).toBe(afterReturn);
    const history = await api(`/api/members/${member.id}/history`);
    expect(history.charges).toHaveLength(2);
    expect(history.charges.some(record => record.amountCents===5000)).toBe(true);

    // Prepare anonymous entitlements through the actual UI, without touching the member account.
    await page.getByTestId('admin-nav-precharges').click();
    await page.getByTestId('precharge-start').click();
    await page.getByTestId('precharge-minutes').fill('60');
    await page.getByTestId('precharge-begin-scan').click();
    for (const uid of ['2283055882','2283055881','2283055883']) {
      await page.keyboard.type(uid); await page.keyboard.press('Enter');
      await expect(page.getByTestId('precharge-last-success')).toContainText(uid);
    }
    await page.keyboard.type('2283055882'); await page.keyboard.press('Enter');
    await expect(page.getByTestId('precharge-error')).toContainText('此手环已预充值');
    await page.getByTestId('precharge-close').click();
    expect((await api('/api/members'))[0].remainingSeconds).toBe(afterReturn);
    expect((await api('/api/members/'+member.id+'/history')).charges).toHaveLength(2);
    expect((await api('/api/dashboard/overview')).revenueTodayCents).toBe(5000);
    const editable = page.getByTestId('precharge-row-2283055883');
    await editable.getByTestId('precharge-edit').click();
    await page.getByTestId('precharge-minutes').fill('30');
    await page.getByTestId('precharge-change-submit').click();
    await expect(page.getByTestId('precharge-dialog')).toHaveCount(0);
    await expect(editable).toContainText('30 分钟');
    await editable.getByTestId('precharge-cancel').click();
    await page.getByTestId('precharge-change-submit').click();
    await expect(editable).toHaveCount(0);
    await page.getByTestId('precharge-search').fill('2283055882');
    await page.getByTestId('precharge-refresh').click();
    await expect(page.locator('[data-testid^="precharge-row-"]')).toHaveCount(1);
    await page.getByTestId('precharge-search').fill('');
    await page.getByTestId('precharge-refresh').click();
    await expect(page.locator('[data-testid^="precharge-row-"]')).toHaveCount(2);
    await page.screenshot({path:test.info().outputPath('手环预充值-连续刷卡与待激活列表.png'),fullPage:true});
    await page.getByTestId('admin-nav-members').click();
    await row.getByTestId('admin-member-issue').click();
    await page.keyboard.type('2283055882'); await page.keyboard.press('Enter');
    await expect(page.getByTestId('card-minutes')).toHaveValue('60');
    await expect(page.getByTestId('card-minutes')).toBeDisabled();
    await page.getByTestId('card-submit').click();
    await expect(page.getByTestId('card-confirmation')).toHaveCount(0);
    expect((await api('/api/members'))[0].remainingSeconds).toBe(afterReturn);
    const counterBand = await api('/api/wristbands/2283055882');
    expect(counterBand.status).toBe('ACTIVE');
    await page.getByTestId('admin-nav-rooms').click();
    await page.getByTestId('admin-card-return').click();
    await page.keyboard.type('2283055882'); await page.keyboard.press('Enter');
    await expect(page.getByTestId('card-confirmation')).toBeVisible();
    await page.getByTestId('card-submit').click();
    await expect(page.getByTestId('card-confirmation')).toHaveCount(0);
    const afterCounterReturn = (await api('/api/members'))[0].remainingSeconds;
    expect(afterCounterReturn).toBeGreaterThan(afterReturn);
    expect(afterCounterReturn).toBeLessThanOrEqual(afterReturn+3600);
    const kioskExe = path.join(root,'release/registration-kiosk/win-unpacked/LED Game 自助注册端.exe');
    kiosk = await electron.launch({ executablePath:kioskExe, cwd:path.dirname(kioskExe), env:{...env, LEDGAME_USER_DATA:path.join(directory,'kiosk')} });
    const operator = await kiosk.firstWindow();
    await operator.getByTestId('operator-host').fill('127.0.0.1');
    await operator.getByTestId('operator-port').fill(String(port));
    await operator.getByTestId('operator-save').click();
    await operator.getByTestId('operator-test').click();
    await expect(operator.getByTestId('operator-launch')).toBeEnabled();
    const customerPromise = kiosk.waitForEvent('window');
    await operator.getByTestId('operator-launch').click();
    const customer = await customerPromise;
    await customer.getByTestId('kiosk-activate').click();
    await customer.getByTestId('kiosk-member-phone').fill('13800138880');
    await customer.getByTestId('kiosk-member-submit').click();
    await customer.getByTestId('kiosk-existing-continue').click();
    await customer.getByTestId('kiosk-scan-start').click();
    await customer.keyboard.type('2283055881'); await customer.keyboard.press('Enter');
    await expect(customer.getByTestId('kiosk-screen-success')).toBeVisible();
    const activated = await api('/api/wristbands/2283055881');
    expect(activated.status).toBe('ACTIVE');
    expect((await api('/api/members'))[0].remainingSeconds).toBe(afterCounterReturn);
    await expect.poll(async () => (await api('/api/wristbands/2283055881')).remainingSeconds).toBeLessThan(3600);
    expect((await api('/api/members/'+member.id+'/history')).charges).toHaveLength(4);
    expect((await api('/api/dashboard/overview')).revenueTodayCents).toBe(5000);
    await customer.screenshot({path:test.info().outputPath('自助激活-立即计时.png')});
  } finally {
    if (kiosk) {
      await kiosk.evaluate(({BrowserWindow}) => { for(const window of BrowserWindow.getAllWindows()) window.destroy(); }).catch(()=>{});
      await kiosk.close().catch(()=>{});
    }
    if(admin) await admin.close().catch(()=>{});
    await fs.rm(directory,{recursive:true,force:true});
  }
});
