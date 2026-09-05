import path from "node:path";
import { failCli, parseArgs, requiredArg } from "../cli.js";
import { readJsonFile, resolveCenterConfig } from "../config.js";
import { startCenter } from "../center.js";

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const configPath = path.resolve(requiredArg(args, "config"));
  const config = resolveCenterConfig(await readJsonFile<Record<string, unknown>>(configPath), process.cwd());
  const connection = await startCenter(config);
  process.stdout.write([
    "会员管理端并发测试实例已启动。",
    `运行编号：${connection.runId}`,
    `压力机连接地址：${connection.platformBaseUrl}`,
    `请把连接文件复制到 B、C 机器：${path.join(connection.runRoot, "connection.json")}`,
    "测试结束后请从会员管理端窗口正常退出。",
  ].join("\n") + "\n");
}

main().catch(failCli);
