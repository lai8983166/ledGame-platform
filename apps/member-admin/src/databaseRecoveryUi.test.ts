import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const source = readFileSync(resolve(fileURLToPath(import.meta.url), "../views/SettingsView.vue"), "utf8");

describe("database recovery operator workflow", () => {
  it("explains the request materials and never asks the operator to send the database", () => {
    expect(source).toContain("生成跨系统恢复请求");
    expect(source).toContain("factory-key-envelope.json");
    expect(source).toContain("无需发送 platform.db、avatars 头像目录或 CSV");
    expect(source).toContain("备份 revision");
    expect(source).toContain("数据库摘要");
  });

  it("requires an explicit confirmation before selecting and importing a vendor response", () => {
    expect(source).toContain("openRecoveryResponseWizard");
    expect(source).toContain("覆盖当前主数据库");
    expect(source).toContain("选择文件并继续");
    expect(source).toContain("取消或校验失败不会修改当前数据库和正式备份");
  });

  it("maps recovery failures to actionable Chinese messages", () => {
    expect(source).toContain("恢复请求已过期，请重新生成请求并联系厂家。");
    expect(source).not.toContain("DATABASE_RECOVERY_RESPONSE_SIGNATURE_INVALID");
    expect(source).toContain("当前有游戏或排队流程正在进行，请结束营业后再恢复。");
  });
});
