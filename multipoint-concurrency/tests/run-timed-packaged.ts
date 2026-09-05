import fs from "node:fs/promises";
import path from "node:path";
import net from "node:net";
import { spawn, execFile, type ChildProcess } from "node:child_process";
import { promisify } from "node:util";
import { startCenter, createCenterRunPaths } from "../src/center.js";
import { generateRunId, resolveAgentConfig } from "../src/config.js";
import { runAgent } from "../src/agent.js";
import { buildPlan } from "../src/plan.js";
import { renderPreview } from "../src/timed-plan.js";
import { verifyRun, writeVerificationReport } from "../src/verifier.js";
import { SAFETY_CONFIRMATION, type CenterConfig } from "../src/types.js";

async function freePort(): Promise<number> {
  return new Promise(resolve => { const server = net.createServer(); server.listen(0, "127.0.0.1", () => {
    const port = (server.address() as net.AddressInfo).port; server.close(() => resolve(port));
  }); });
}

async function main() {
  const config: CenterConfig = { runId: generateRunId(),
    memberAdminExecutable: path.resolve("release/member-admin/win-unpacked/LED Game 会员管理端.exe"),
    testRoot: path.resolve("test-results/multipoint-timed-packaged"), lanHost: "127.0.0.1", testPort: await freePort(),
    normalPlatformUrl: "http://127.0.0.1:8090", startupTimeoutMs: 60000, safetyConfirmation: SAFETY_CONFIRMATION };
  let child: ChildProcess | undefined;
  const paths = createCenterRunPaths(config);
  try {
    const connection = await startCenter(config, { spawnProcess(exe, env) {
      const cleanEnv = { ...env }; delete cleanEnv.ELECTRON_RUN_AS_NODE;
      child = spawn(exe, [], { env: cleanEnv, stdio: "ignore", windowsHide: true }); return child;
    } });
    const configs = ["B", "C"].map(agentId => resolveAgentConfig(connection, {
      agentId, profile: "quick", outputRoot: config.testRoot, safetyConfirmation: SAFETY_CONFIRMATION }));
    const preview = renderPreview(configs.map(c => buildPlan(c)));
    await fs.writeFile(path.join(paths.runRoot, "双节点执行预览.md"), preview, "utf8");
    process.stdout.write(preview + "\n");
    await Promise.all(configs.map(c => runAgent(c, { onProgress: message => process.stdout.write(`[${c.agentId}] ${message}\n`) })));
    const outputDirectory = path.join(paths.runRoot, "report");
    const report = await verifyRun({ connectionFile: paths.connectionFile,
      agentDirectories: configs.map(c => path.join(config.testRoot, c.runId, c.agentId)),
      outputDirectory, requestTimeoutMs: 5000, performanceWarningP95Ms: 2000 });
    await writeVerificationReport(report, outputDirectory);
    process.stdout.write(`打包版五分钟双节点：${report.conclusion}\n报告：${outputDirectory}\n`);
    if (report.conclusion !== "PASSED") throw new Error(JSON.stringify(report.differences));
  } finally {
    // 集成测试只正常关闭自己启动的已知 PID；不结束未知 Electron/Java。
    if (child?.pid && child.exitCode === null) {
      await promisify(execFile)("powershell.exe", ["-NoProfile", "-Command", `(Get-Process -Id ${child.pid}).CloseMainWindow()`], { windowsHide: true });
      await new Promise<void>((resolve, reject) => {
        if (child!.exitCode !== null) { resolve(); return; }
        const timer = setTimeout(() => reject(new Error(`测试实例 ${child!.pid} 未正常退出，请检查 ${paths.runRoot}`)), 30000);
        child!.once("exit", () => { clearTimeout(timer); resolve(); });
      });
    }
  }
}
main().catch(error => { process.stderr.write(`${error instanceof Error ? error.stack : error}\n`); process.exitCode = 1; });
