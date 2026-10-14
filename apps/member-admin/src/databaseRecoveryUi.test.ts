import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const source = readFileSync(resolve(fileURLToPath(import.meta.url), "../views/SettingsView.vue"), "utf8");

describe("simplified database maintenance", () => {
  it("only exposes backup path, full export and import on the database page", () => {
    expect(source).toContain('data-testid="database-backup-path"');
    expect(source).toContain('data-testid="database-export"');
    expect(source).toContain('data-testid="database-import"');
    expect(source).not.toContain("生成跨系统恢复请求");
    expect(source).not.toContain("factory-key-envelope.json");
    expect(source).not.toContain("backupCandidates");
  });

  it("requires confirmation of version and overwrite before importing", () => {
    expect(source).toContain("覆盖当前主数据库");
    expect(source).toContain("pendingImport.revision");
    expect(source).toContain("pendingImport.lastBusinessModifiedAt");
    expect(source).toContain('data-testid="database-import-confirm"');
  });

  it("keeps factory scope, visible errors and relogin after restore", () => {
    expect(source).toContain('...(isFactory.value ? [backupTab] : [])');
    expect(source).toContain('emit("logoutRequired")');
    expect(source).toContain('v-if="backupError"');
    expect(source).toContain("window.memberAdminDesktop.exportDatabase(operator.id)");
  });
});
