import fs from 'node:fs/promises';
import path from 'node:path';

// 只为隔离的桌面验收目录预置本机授权；不是正式应用的绕过开关。
export async function prepareActivationLicense(userData) {
  const source = process.env.LEDGAME_TEST_LICENSE_PATH;
  if (!source) throw new Error('此桌面业务验收需要本机厂家授权，请设置 LEDGAME_TEST_LICENSE_PATH 指向本机有效的 activation/license.json');
  const info = await fs.stat(source);
  if (!info.isFile() || info.size > 16640) throw new Error('测试授权文件无效或过大');
  const contents = await fs.readFile(source);
  const directory = path.join(userData, 'activation');
  const target = path.join(directory, 'license.json');
  await fs.mkdir(directory, { recursive: true });
  try { await fs.writeFile(target, contents, { flag: 'wx' }); }
  catch (error) {
    if (error.code !== 'EEXIST' || !(await fs.readFile(target)).equals(contents)) throw error;
  }
}
