import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "..");
const releaseRoot = path.join(root, "release");
const manifestJson = path.join(releaseRoot, "打包清单.json");
const manifestMarkdown = path.join(releaseRoot, "打包清单.md");
const dryRun = process.argv.includes("--dry-run");

const steps = [
  { name: "会员管理端（win-unpacked + ZIP）", script: "portable:member-admin" },
  { name: "自助注册端（win-unpacked + ZIP）", script: "portable:registration" },
  { name: "离线激活签发工具（win-unpacked + ZIP + 单文件 EXE）", script: "portable:activation-signer" },
  { name: "多点并发测试工具", script: "portable:multipoint-concurrency" },
  { name: "游戏端烤机工具", script: "portable:game-soak:release" },
];

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    stdio: options.capture ? "pipe" : "inherit",
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    const detail = options.capture ? String(result.stderr || result.stdout || "").trim() : "";
    throw new Error(`${command} ${args.join(" ")} 执行失败${detail ? `：${detail}` : ""}`);
  }
  return options.capture ? String(result.stdout || "").trim() : "";
}

function runPnpm(script) {
  if (process.platform === "win32") {
    run(process.env.ComSpec || "C:\\Windows\\System32\\cmd.exe", ["/d", "/s", "/c", `pnpm.cmd run ${script}`]);
    return;
  }
  run("pnpm", ["run", script]);
}

async function sha256(file) {
  const hash = crypto.createHash("sha256");
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
}

async function describeArtifact(relativePath) {
  const absolutePath = path.join(root, relativePath);
  const stat = await fs.stat(absolutePath).catch(() => null);
  if (!stat?.isFile()) throw new Error(`统一打包产物缺失：${relativePath}`);
  return {
    path: relativePath.replaceAll("\\", "/"),
    bytes: stat.size,
    sha256: await sha256(absolutePath),
  };
}

async function gitValue(args, fallback) {
  try { return run("git", args, { capture: true }) || fallback; }
  catch { return fallback; }
}

async function writeManifest() {
  const packageJson = JSON.parse(await fs.readFile(path.join(root, "package.json"), "utf8"));
  const version = String(packageJson.version);
  const artifacts = {
    memberAdmin: await Promise.all([
      describeArtifact("release/member-admin/win-unpacked/LED Game 会员管理端.exe"),
      describeArtifact(`release/member-admin/LED Game 会员管理端-${version}-win.zip`),
    ]),
    registrationKiosk: await Promise.all([
      describeArtifact("release/registration-kiosk/win-unpacked/LED Game 自助注册端.exe"),
      describeArtifact(`release/registration-kiosk/LED Game 自助注册端-${version}-win.zip`),
    ]),
    activationSigner: await Promise.all([
      describeArtifact("release/activation-signer/win-unpacked/LED Game 激活码签发工具.exe"),
      describeArtifact(`release/activation-signer/LEDGame-Activation-Signer-${version}-x64.zip`),
      describeArtifact(`release/activation-signer/LEDGame-Activation-Signer-${version}-x64.exe`),
    ]),
    multipointConcurrency: await Promise.all([
      describeArtifact("release/multipoint-concurrency/runtime/node.exe"),
      describeArtifact("release/multipoint-concurrency/center.cmd"),
      describeArtifact("release/multipoint-concurrency/agent.cmd"),
      describeArtifact("release/multipoint-concurrency/verify.cmd"),
    ]),
    gameSoak: await Promise.all([
      describeArtifact("release/game-soak/runtime/node.exe"),
      describeArtifact("release/game-soak/soak.cmd"),
      describeArtifact("release/game-soak/config.example.json"),
    ]),
  };
  const manifest = {
    formatVersion: 1,
    builtAt: new Date().toISOString(),
    packageVersion: version,
    sourceCommit: await gitValue(["rev-parse", "HEAD"], "UNKNOWN"),
    sourceHasTrackedChanges: (await gitValue(["status", "--short", "--untracked-files=no"], "UNKNOWN")) !== "",
    artifacts,
  };
  await fs.mkdir(releaseRoot, { recursive: true });
  await fs.writeFile(manifestJson, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

  const lines = [
    "# LED Game Platform 统一打包清单", "",
    `- 构建时间：${manifest.builtAt}`,
    `- 版本：${version}`,
    `- Git 提交：${manifest.sourceCommit}`,
    `- 构建时存在未提交的已跟踪修改：${manifest.sourceHasTrackedChanges ? "是" : "否"}`,
    "", "## 交付目录", "",
    "- `release/member-admin`：会员管理端目录包和 ZIP",
    "- `release/registration-kiosk`：自助注册端目录包和 ZIP",
    "- `release/activation-signer`：厂家离线激活签发工具",
    "- `release/multipoint-concurrency`：打包版多点并发测试工具",
    "- `release/game-soak`：打包版游戏端烤机工具",
    "", "详细文件大小和 SHA-256 校验值见 `打包清单.json`。授权文件、厂家私钥、业务数据库和测试结果不会打进交付目录。", "",
  ];
  await fs.writeFile(manifestMarkdown, lines.join("\n"), "utf8");
}

async function main() {
  if (process.platform !== "win32" && !dryRun) throw new Error("统一 Windows 打包必须在 Windows 上运行");
  process.stdout.write("LED Game Platform 统一打包内容：\n");
  steps.forEach((step, index) => process.stdout.write(`${index + 1}. ${step.name}\n`));
  if (dryRun) {
    process.stdout.write("\n仅检查打包计划，未生成或修改 release 产物。\n");
    return;
  }

  await fs.rm(manifestJson, { force: true });
  await fs.rm(manifestMarkdown, { force: true });
  for (const [index, step] of steps.entries()) {
    process.stdout.write(`\n[${index + 1}/${steps.length}] 正在打包：${step.name}\n`);
    runPnpm(step.script);
  }
  process.stdout.write("\n正在校验会员管理端与自助注册端发布结构……\n");
  runPnpm("portable:verify");
  await writeManifest();
  process.stdout.write(`\n全部产物已更新。\n中文清单：${manifestMarkdown}\nJSON 清单：${manifestJson}\n`);
}

main().catch((error) => {
  process.stderr.write(`统一打包失败：${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
