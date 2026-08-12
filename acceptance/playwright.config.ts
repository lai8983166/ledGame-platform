import { defineConfig } from "@playwright/test";
import path from "node:path";

const artifactsRoot = path.resolve(process.cwd(), "acceptance", "artifacts");

export default defineConfig({
  testDir: "./specs",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir: path.join(artifactsRoot, "test-results"),
  reporter: [
    ["list"],
    ["html", { outputFolder: path.join(artifactsRoot, "report"), open: "never" }],
    [path.resolve(process.cwd(), "acceptance", "support", "chineseReporter.ts"), {
      outputFile: path.join(artifactsRoot, "测试报告.md"),
    }],
  ],
  use: {
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
});
