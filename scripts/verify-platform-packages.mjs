import fs from "node:fs/promises";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const member = path.join(root, "release", "member-admin");
const kiosk = path.join(root, "release", "registration-kiosk");

async function exists(target) {
  return fs.stat(target).then(() => true).catch(() => false);
}

async function walk(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map(async (entry) => {
    const target = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(target) : [target];
  }))).flat();
}

const memberExe = path.join(member, "win-unpacked", "LED Game 会员管理端.exe");
const kioskExe = path.join(kiosk, "win-unpacked", "LED Game 自助注册端.exe");
const required = [
  memberExe,
  kioskExe,
  path.join(member, "win-unpacked", "resources", "backend", "ledgame-platform-server.jar"),
  path.join(member, "win-unpacked", "resources", "jre", "bin", "java.exe"),
];
for (const target of required) if (!await exists(target)) throw new Error(`打包产物缺失：${target}`);

const memberFiles = await fs.readdir(member);
const kioskFiles = await fs.readdir(kiosk);
if (!memberFiles.some((name) => name.endsWith("-win.zip"))) throw new Error("会员管理端 ZIP 缺失");
if (!kioskFiles.some((name) => name.endsWith("-win.zip"))) throw new Error("自助注册端 ZIP 缺失");

const kioskContents = (await walk(path.join(kiosk, "win-unpacked"))).map((file) => path.relative(kiosk, file).toLowerCase());
const forbidden = kioskContents.find((file) => /(^|[\\/])(backend|jre)([\\/]|$)|\.db$|sqlite/.test(file));
if (forbidden) throw new Error(`自助注册端包含不应携带的服务端或数据资源：${forbidden}`);

const desktopSources = (await walk(path.join(root, "desktop"))).filter((file) => /\.(?:cjs|mjs|json)$/.test(file));
for (const file of desktopSources) {
  const source = await fs.readFile(file, "utf8");
  if (/webSecurity\s*:\s*false/.test(source)) throw new Error(`发现关闭 webSecurity：${file}`);
}

process.stdout.write("Windows 产品包结构与安全契约检查通过。\n");
