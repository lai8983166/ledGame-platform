import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const root = path.resolve(import.meta.dirname, "../..");

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(root, relativePath), "utf8"));
}

describe("Windows desktop package contract", () => {
  it("uses independent identities and output directories", () => {
    const member = readJson("desktop/electron-builder.member-admin.json");
    const kiosk = readJson("desktop/electron-builder.registration-kiosk.json");
    expect(member.appId).not.toBe(kiosk.appId);
    expect(member.directories.output).not.toBe(kiosk.directories.output);
  });

  it("keeps backend, JRE and SQLite out of the kiosk package", () => {
    const kiosk = readJson("desktop/electron-builder.registration-kiosk.json");
    const serialized = JSON.stringify(kiosk).toLowerCase();
    expect(serialized).not.toContain("backend");
    expect(serialized).not.toContain("jre");
    expect(serialized).not.toContain("sqlite");
  });
});
