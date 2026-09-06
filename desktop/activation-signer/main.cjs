const { app, BrowserWindow, ipcMain, dialog, clipboard } = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createPrivateKey } = require('node:crypto');

app.setName('LED Game Activation Signer');
if (process.env.LEDGAME_SIGNER_USER_DATA) app.setPath('userData', path.resolve(process.env.LEDGAME_SIGNER_USER_DATA));
let window;
let keyPath = '';
let lastCode = '';
const settingsFile = () => path.join(app.getPath('userData'), 'signer-settings.json');
async function readKey(file) {
  try {
    const info = await fs.stat(file);
    if (!info.isFile() || info.size > 16384) throw new Error();
    const pem = await fs.readFile(file, 'utf8');
    if (createPrivateKey(pem).asymmetricKeyType !== 'ed25519') throw new Error();
    return pem;
  } catch { throw new Error('无法读取有效的 Ed25519 私钥，请重新选择厂家私钥 txt 或 pem 文件'); }
}
function restoreFocus() {
  const focus = () => {
    if (!window || window.isDestroyed()) return;
    window.show(); window.focus(); window.webContents.focus();
  };
  focus(); setImmediate(focus); setTimeout(focus, 50);
}
function handle(name, action) {
  ipcMain.handle(`signer:${name}`, async (event, ...args) => {
    if (event.sender !== window?.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('不允许的窗口请求');
    try { return { ok: true, value: await action(...args) }; }
    catch (error) { return { ok: false, message: error.message }; }
  });
}
app.whenReady().then(async () => {
  try { const data = JSON.parse(await fs.readFile(settingsFile(), 'utf8')); if (typeof data.keyPath === 'string') keyPath = data.keyPath; } catch { /* 首次启动或设置损坏时重新选择 */ }
  handle('status', () => ({ keyPath }));
  handle('choose-key', async () => {
    let selection;
    try {
      selection = await dialog.showOpenDialog(window, { title: '选择厂家私钥（不会复制到程序中）', properties: ['openFile'], filters: [{ name: '厂家私钥', extensions: ['txt', 'pem', 'key'] }] });
    } finally { restoreFocus(); }
    if (selection.canceled || !selection.filePaths.length) return null;
    const selected = selection.filePaths[0];
    await readKey(selected);
    await fs.mkdir(app.getPath('userData'), { recursive: true });
    try { await fs.writeFile(settingsFile(), JSON.stringify({ keyPath: selected }), 'utf8'); }
    catch { throw new Error('无法保存私钥路径，请检查工具用户目录权限'); }
    keyPath = selected; lastCode = '';
    return { keyPath };
  });
  handle('issue', async (machine) => {
    lastCode = '';
    if (!keyPath) throw new Error('请先选择厂家私钥');
    if (typeof machine !== 'string' || machine.length > 256) throw new Error('机器码格式不正确');
    const normalized = machine.replace(/[ \t\r\n]/g, '');
    const { issueCode } = await import('../../scripts/activation-tool.mjs');
    lastCode = issueCode(await readKey(keyPath), normalized);
    return lastCode;
  });
  handle('copy', () => {
    if (!lastCode) throw new Error('请先生成激活码');
    try { clipboard.writeText(lastCode); } catch { throw new Error('复制失败，请手动选择下方激活码并按 Ctrl+C'); }
  });
  window = new BrowserWindow({ width: 760, height: 760, minWidth: 660, minHeight: 620, autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  window.setMenu(null);
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  await window.loadFile(path.join(__dirname, 'index.html'));
});
app.on('window-all-closed', () => app.quit());
