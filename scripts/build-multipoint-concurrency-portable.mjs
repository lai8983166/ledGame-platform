import fs from "node:fs/promises";
import path from "node:path";
import { spawnSync } from "node:child_process";

const projectRoot = path.resolve(import.meta.dirname, "..");
const buildRoot = path.join(projectRoot, ".build", "multipoint-concurrency");
const outputRoot = path.join(projectRoot, "release", "multipoint-concurrency");
for (const target of [buildRoot, outputRoot]) {
  const relative = path.relative(projectRoot, target);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) throw new Error(`拒绝清理工作区外目录：${target}`);
  await fs.rm(target, { recursive: true, force: true });
}
const tsc = path.join(projectRoot, "node_modules", "typescript", "bin", "tsc");
const compile = spawnSync(process.execPath, [tsc, "-p", path.join(projectRoot, "multipoint-concurrency", "tsconfig.build.json")], { cwd: projectRoot, stdio: "inherit" });
if (compile.status !== 0) process.exit(compile.status ?? 1);
await fs.mkdir(path.join(outputRoot, "runtime"), { recursive: true });
await fs.copyFile(process.execPath, path.join(outputRoot, "runtime", "node.exe"));
await fs.cp(path.join(buildRoot, "app"), path.join(outputRoot, "app"), { recursive: true });
await fs.cp(path.join(projectRoot, "multipoint-concurrency", "portable", "config"), path.join(outputRoot, "config"), { recursive: true });
await fs.copyFile(path.join(projectRoot, "docs", "打包版多点并发验收使用说明.md"), path.join(outputRoot, "使用说明.md"));
await fs.writeFile(path.join(outputRoot, "center.cmd"), '@echo off\r\npushd "%~dp0"\r\n"runtime\\node.exe" "app\\commands\\center.js" --config "config\\center.json" %*\r\npopd\r\n', "utf8");
await fs.writeFile(path.join(outputRoot, "agent.cmd"), '@echo off\r\npushd "%~dp0"\r\n"runtime\\node.exe" "app\\commands\\agent.js" %*\r\npopd\r\n', "utf8");
await fs.writeFile(path.join(outputRoot, "verify.cmd"), '@echo off\r\npushd "%~dp0"\r\n"runtime\\node.exe" "app\\commands\\verify.js" %*\r\npopd\r\n', "utf8");
await fs.writeFile(path.join(outputRoot, "NODE-RUNTIME-LICENSE.txt"), "Node.js is distributed under the MIT license and includes third-party software.\r\nCopyright Node.js contributors. All rights reserved.\r\nFull license and third-party notices for this runtime version: https://github.com/nodejs/node/blob/main/LICENSE\r\n", "utf8");
for (const forbidden of ["node_modules", "pnpm-lock.yaml", "pom.xml"]) {
  const found = await fs.stat(path.join(outputRoot, forbidden)).then(() => true).catch(() => false);
  if (found) throw new Error(`便携目录包含禁止内容：${forbidden}`);
}
const selfCheck = spawnSync(path.join(outputRoot, "runtime", "node.exe"), [path.join(outputRoot, "app", "self-check.js"), outputRoot], { cwd: outputRoot, encoding: "utf8" });
if (selfCheck.status !== 0) throw new Error(selfCheck.stderr || "便携发布物自检失败");
await fs.rm(path.join(outputRoot, ".self-check"), { recursive: true, force: true });
await fs.rm(buildRoot, { recursive: true, force: true });
process.stdout.write(`${selfCheck.stdout}便携工具已生成：${outputRoot}\n`);
