import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const root = path.resolve(import.meta.dirname, "../..");

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(root, relativePath), "utf8"));
}

describe("Windows desktop package contract", () => {
  it("starts each Vite renderer on the port awaited by its Electron shell", () => {
    const rootPackage = readJson("package.json");
    const memberPackage = readJson("apps/member-admin/package.json");
    const kioskPackage = readJson("apps/registration-kiosk/package.json");

    expect(rootPackage.scripts["dev:member-admin"]).toBe("pnpm --dir apps/member-admin dev");
    expect(memberPackage.scripts.dev).toContain("--host 127.0.0.1 --port 5177");
    expect(rootPackage.scripts["dev:member-admin:desktop"]).toContain("wait-on tcp:127.0.0.1:5177");
    expect(rootPackage.scripts["dev:member-admin:desktop"]).toContain("node desktop/shared/electron-launcher.cjs desktop/member-admin/main.cjs");

    expect(rootPackage.scripts["dev:registration"]).toBe("pnpm --dir apps/registration-kiosk dev");
    expect(kioskPackage.scripts.dev).toContain("--host 127.0.0.1 --port 5176");
    expect(rootPackage.scripts["dev:registration:desktop"]).toContain("wait-on tcp:127.0.0.1:5176");
    expect(rootPackage.scripts["dev:registration:desktop"]).toContain("node desktop/shared/electron-launcher.cjs desktop/registration-kiosk/main.cjs");
  });

  it("uses independent identities and output directories", () => {
    const member = readJson("desktop/electron-builder.member-admin.json");
    const kiosk = readJson("desktop/electron-builder.registration-kiosk.json");
    expect(member.appId).not.toBe(kiosk.appId);
    expect(member.directories.output).not.toBe(kiosk.directories.output);
  });

  it("packages the isolated startup check window without exposing an import action there", () => {
    const member = readJson("desktop/electron-builder.member-admin.json");
    expect(member.files).toContain("desktop/member-admin/**/*");
    const startup = fs.readFileSync(path.join(root, "desktop/member-admin/startup.html"), "utf8");
    expect(startup).toContain("正在检查本机数据");
    expect(startup).not.toContain("导入");
    expect(startup).not.toContain("candidate");
  });

  it("restores the member renderer focus after the native backup file picker", () => {
    const main = fs.readFileSync(path.join(root, "desktop/member-admin/main.cjs"), "utf8");
    expect(main).toContain("dialog.showOpenDialog(mainWindow");
    expect(main).toContain("mainWindow.webContents.focus()");
  });

  it("uses a single instance and closes the hidden startup window before normal operation", () => {
    const main = fs.readFileSync(path.join(root, "desktop/member-admin/main.cjs"), "utf8");
    expect(main).toContain("app.requestSingleInstanceLock()");
    expect(main).toContain('app.on("second-instance"');
    expect(main).toContain('mainWindow.on("closed"');
    expect(main).toContain("startupWindow.destroy()");
  });

  it("keeps backend, JRE and SQLite out of the kiosk package", () => {
    const kiosk = readJson("desktop/electron-builder.registration-kiosk.json");
    const serialized = JSON.stringify(kiosk).toLowerCase();
    expect(serialized).not.toContain("backend");
    expect(serialized).not.toContain("jre");
    expect(serialized).not.toContain("sqlite");
  });
});
