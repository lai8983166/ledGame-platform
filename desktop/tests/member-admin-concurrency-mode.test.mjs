import fs from "node:fs";
import { describe, expect, it } from "vitest";

describe("member admin isolated concurrency marker", () => {
  it("passes the environment marker to both startup and main windows", () => {
    const main = fs.readFileSync("desktop/member-admin/main.cjs", "utf8");
    const startup = fs.readFileSync("desktop/member-admin/startup.html", "utf8");
    const app = fs.readFileSync("apps/member-admin/src/App.vue", "utf8");
    expect(main).toContain("LEDGAME_CONCURRENCY_TEST_RUN_ID");
    expect(main).toContain("concurrencyTestMode: Boolean(concurrencyTestRunId)");
    expect(startup).toContain("并发测试模式");
    expect(app).toContain('data-testid="concurrency-test-banner"');
  });

  it("does not expose a UI switch that can change the data mode", () => {
    const app = fs.readFileSync("apps/member-admin/src/App.vue", "utf8");
    expect(app).not.toContain("LEDGAME_USER_DATA");
    expect(app).not.toContain("LEDGAME_DATABASE_BACKUP_ENVIRONMENT");
  });
});
