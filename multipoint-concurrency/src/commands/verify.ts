import path from "node:path";
import { allArgs, failCli, optionalArg, parseArgs, requiredArg } from "../cli.js";
import { verifyRun, writeVerificationReport } from "../verifier.js";

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const agentDirectories = allArgs(args, "agent").map((value) => path.resolve(value));
  if (agentDirectories.length < 2) throw new Error("至少需要两个 --agent 结果目录");
  const outputDirectory = path.resolve(optionalArg(args, "output") ?? "report");
  const report = await verifyRun({ connectionFile: path.resolve(requiredArg(args, "connection")), agentDirectories, outputDirectory, requestTimeoutMs: Number(optionalArg(args, "timeout-ms") ?? 5000), performanceWarningP95Ms: Number(optionalArg(args, "warn-p95-ms") ?? 2000) });
  const files = await writeVerificationReport(report, outputDirectory);
  process.stdout.write(`验收结论：${report.conclusion}\n中文报告：${files.markdown}\nJSON 报告：${files.json}\n`);
  if (report.conclusion !== "PASSED") process.exitCode = 2;
}

main().catch(failCli);
