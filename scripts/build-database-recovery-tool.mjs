import fs from "node:fs/promises";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const output = path.resolve(process.argv[2] || path.join(root, "release", "database-recovery-tool"));
await fs.rm(output, { recursive: true, force: true });
await fs.mkdir(output, { recursive: true });
await fs.copyFile(path.join(root, "scripts", "database-recovery-tool.mjs"), path.join(output, "database-recovery-tool.mjs"));
await fs.copyFile(path.join(root, "docs", "厂家协助数据库恢复说明.md"), path.join(output, "使用说明.md"));
const runtimeDirectory = path.join(output, "runtime");
await fs.mkdir(runtimeDirectory, { recursive: true });
if (process.platform === "win32") await fs.copyFile(process.execPath, path.join(runtimeDirectory, "node.exe"));
await fs.writeFile(path.join(output, "database-recovery-tool.cmd"), "@echo off\r\nset \"NODE=%~dp0runtime\\node.exe\"\r\nif not exist \"%NODE%\" set \"NODE=node\"\r\n\"%NODE%\" \"%~dp0database-recovery-tool.mjs\" %*\r\n", "utf8");
await fs.writeFile(path.join(output, "README.txt"), "厂家专用离线恢复工具。目录自带 Node 运行时；私钥必须从受控目录通过 --private-key 指定，不要复制到此目录。\r\n", "utf8");
console.log(`database recovery tool written to ${output}`);
