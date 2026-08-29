import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { FullConfig, FullResult, Suite, TestCase, TestResult } from "@playwright/test/reporter";
import ChineseAcceptanceReporter from "./chineseReporter";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe("中文验收报告", () => {
  it("保留中文业务步骤并隐藏 Playwright 内部断言步骤", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "ledgame-chinese-report-"));
    temporaryDirectories.push(directory);
    const outputFile = path.join(directory, "测试报告.md");
    const reporter = new ChineseAcceptanceReporter({ outputFile });
    reporter.onBegin({} as FullConfig, {} as Suite);
    reporter.onTestEnd(
      { title: "中文业务场景" } as TestCase,
      {
        status: "passed",
        duration: 1_200,
        steps: [
          { title: "中文业务步骤", category: "test.step", duration: 800, steps: [] },
          { title: 'Expect "poll toBe"', category: "test.step", duration: 10, steps: [] },
        ],
      } as unknown as TestResult,
    );
    await reporter.onEnd({ status: "passed" } as FullResult);

    const report = await readFile(outputFile, "utf8");
    expect(report).toContain("本局全局游戏时间");
    expect(report).toContain("不是会员手环可用余额");
    expect(report).toContain("测试结论：通过");
    expect(report).toContain("中文业务场景");
    expect(report).toContain("中文业务步骤：通过");
    expect(report).toContain("Debug 规范化输入");
    expect(report).toContain("PRODUCTION 软件形态");
    expect(report).toContain("未覆盖的现场硬件项目");
    expect(report).not.toContain("Expect");
  });
});
