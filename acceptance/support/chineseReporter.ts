import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import type {
  FullConfig,
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestResult,
  TestStep,
} from "@playwright/test/reporter";

type ReporterOptions = { outputFile?: string };
type RecordedTest = { test: TestCase; result: TestResult };

const statusText: Record<string, string> = {
  passed: "通过",
  failed: "失败",
  timedOut: "超时",
  skipped: "跳过",
  interrupted: "中断",
};

function durationText(milliseconds: number): string {
  if (milliseconds < 1_000) return `${milliseconds} 毫秒`;
  return `${(milliseconds / 1_000).toFixed(1)} 秒`;
}

function visibleSteps(steps: TestStep[]): TestStep[] {
  return steps.filter((step) => step.category === "test.step" && !step.title.startsWith("Expect "));
}

function renderStep(step: TestStep, indent = "  "): string[] {
  const state = step.error ? "失败" : "通过";
  const lines = [`${indent}- ${step.title}：${state}（${durationText(step.duration)}）`];
  for (const child of visibleSteps(step.steps)) lines.push(...renderStep(child, `${indent}  `));
  return lines;
}

export default class ChineseAcceptanceReporter implements Reporter {
  readonly #outputFile: string;
  readonly #tests: RecordedTest[] = [];
  #startedAt = new Date();

  constructor(options: ReporterOptions = {}) {
    this.#outputFile = path.resolve(options.outputFile || "acceptance/artifacts/测试报告.md");
  }

  onBegin(_config: FullConfig, _suite: Suite): void {
    this.#startedAt = new Date();
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    this.#tests.push({ test, result });
  }

  async onEnd(result: FullResult): Promise<void> {
    const passed = this.#tests.filter((item) => item.result.status === "passed").length;
    const failed = this.#tests.filter((item) => ["failed", "timedOut", "interrupted"].includes(item.result.status)).length;
    const skipped = this.#tests.filter((item) => item.result.status === "skipped").length;
    const totalDuration = this.#tests.reduce((sum, item) => sum + item.result.duration, 0);
    const lines = [
      "# 门店核心流程验收测试报告",
      "",
      `- 测试结论：${result.status === "passed" ? "通过" : "未通过"}`,
      `- 开始时间：${this.#startedAt.toLocaleString("zh-CN", { hour12: false })}`,
      `- 场景总数：${this.#tests.length}`,
      `- 通过：${passed}`,
      `- 失败：${failed}`,
      `- 跳过：${skipped}`,
      `- 场景累计耗时：${durationText(totalDuration)}`,
      "",
      "## 覆盖边界",
      "",
      "- Debug 规范化输入：Debug 画布通过与生产地砖相同的 DOWN/UP 领域事件驱动游戏规则，并由黄金流程核对自然结算。",
      "- PRODUCTION 软件形态：游戏以 PRODUCTION 模式运行，经双向 TCP 地砖替身校验 LED 帧、回写生产格式输入并核对自然结算。",
      "- 未覆盖的现场硬件项目：实体 ELC-408/控制器发现、真实地砖坐标与防抖、LED 实物显示、物理断线重连和门店局域网仍需现场冒烟。",
      "",
      "## 场景明细",
      "",
    ];

    for (const { test, result: testResult } of this.#tests) {
      lines.push(`### ${test.title}`);
      lines.push("");
      lines.push(`- 结果：${statusText[testResult.status] || testResult.status}`);
      lines.push(`- 耗时：${durationText(testResult.duration)}`);
      const steps = visibleSteps(testResult.steps);
      if (steps.length) {
        lines.push("- 业务步骤：");
        for (const step of steps) lines.push(...renderStep(step));
      }
      if (testResult.error?.message) {
        lines.push("- 失败原因：");
        lines.push("");
        lines.push("```text");
        lines.push(testResult.error.message);
        lines.push("```");
      }
      lines.push("");
    }

    lines.push("## 相关材料");
    lines.push("");
    lines.push("- 可交互 HTML 报告：`acceptance/artifacts/report/index.html`");
    lines.push("- 截图、录像、执行轨迹（trace）和诊断附件：`acceptance/artifacts/test-results`");
    lines.push("- 使用说明：`acceptance/README.md`");
    lines.push("");
    lines.push("> Playwright HTML 查看器自身的固定按钮可能显示英文；测试场景、业务步骤、诊断附件及本报告均使用中文。");
    lines.push("");

    await mkdir(path.dirname(this.#outputFile), { recursive: true });
    await writeFile(this.#outputFile, `${lines.join("\n")}\n`, "utf8");
  }
}
