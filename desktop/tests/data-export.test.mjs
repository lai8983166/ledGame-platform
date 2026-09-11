import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { ensureUtf8Bom, exportDataset, resolveDataset } = require("../member-admin/data-export.cjs");

describe("member-admin operational CSV export", () => {
  it("rejects a dataset outside the fixed whitelist", () => {
    assert.throws(() => resolveDataset("../../platform.db"), /不支持/);
  });

  it("adds exactly one UTF-8 BOM", () => {
    assert.deepEqual([...ensureUtf8Bom("标题\r\n")].slice(0, 3), [0xef, 0xbb, 0xbf]);
    assert.equal(ensureUtf8Bom("\uFEFF标题").toString("utf8"), "\uFEFF标题");
  });

  it("cancels without requesting or writing and restores focus", async () => {
    const calls = [];
    const window = { show: () => calls.push("show"), focus: () => calls.push("focus"), webContents: { focus: () => calls.push("web") } };
    const result = await exportDataset({ key: "members", operatorId: 1, window,
      dialog: { showSaveDialog: async () => ({ canceled: true }) },
      transport: async () => { throw new Error("must not request"); } });
    assert.deepEqual(result, { canceled: true });
    assert.deepEqual(calls, ["show", "focus", "web"]);
  });

  it("requests the selected endpoint and atomically saves the response", async () => {
    const calls = [];
    const fileSystem = {
      writeFileSync: (file, bytes, options) => calls.push(["write", file, bytes.toString("utf8"), options.flag]),
      renameSync: (source, target) => calls.push(["rename", source, target]),
      existsSync: () => false,
    };
    const result = await exportDataset({ key: "game-plays", operatorId: 9, window: {},
      dialog: { showSaveDialog: async () => ({ canceled: false, filePath: "C:/output/plays.csv" }) },
      transport: async (request) => { calls.push(["request", request]); return { status: 200, body: "a,b\r\n1,2\r\n" }; },
      fileSystem, now: new Date("2026-09-08T00:00:00Z") });
    assert.equal(result.canceled, false);
    assert.equal(calls[0][1].path, "/api/exports/game-plays.csv");
    assert.equal(calls[0][1].headers["X-Operator-Id"], 9);
    assert.equal(calls[1][0], "write");
    assert.equal(calls[2][0], "rename");
  });

  it("reports an API failure without creating a destination file", async () => {
    const writes = [];
    await assert.rejects(() => exportDataset({ key: "members", operatorId: 3, window: {},
      dialog: { showSaveDialog: async () => ({ canceled: false, filePath: "C:/output/members.csv" }) },
      transport: async () => ({ status: 503, body: JSON.stringify({ message: "database busy" }) }),
      fileSystem: { writeFileSync: (...args) => writes.push(args) } }), /database busy/);
    assert.equal(writes.length, 0);
  });

  it("does not create a file when the backend rejects a clerk with 403", async () => {
    const writes = [];
    await assert.rejects(() => exportDataset({ key: "game-plays", operatorId: 3, window: {},
      dialog: { showSaveDialog: async () => ({ canceled: false, filePath: "C:/output/denied.csv" }) },
      transport: async () => ({ status: 403, body: JSON.stringify({
        code: "OPERATOR_FORBIDDEN", message: "当前账号没有执行此操作的权限",
      }) }),
      fileSystem: { writeFileSync: (...args) => writes.push(args) } }), /权限/);
    assert.equal(writes.length, 0);
  });

  it("cleans up the temporary file when the atomic write fails", async () => {
    const calls = [];
    const fileSystem = {
      writeFileSync: (file) => { calls.push(["write", file]); throw new Error("disk full"); },
      renameSync: (...args) => calls.push(["rename", ...args]),
      existsSync: () => true,
      unlinkSync: (file) => calls.push(["unlink", file]),
    };
    await assert.rejects(() => exportDataset({ key: "wristband-charges", operatorId: 4, window: {},
      dialog: { showSaveDialog: async () => ({ canceled: false, filePath: "C:/output/charges.csv" }) },
      transport: async () => ({ status: 200, body: "header\r\n" }), fileSystem }), /disk full/);
    assert.deepEqual(calls.map(([name]) => name), ["write", "unlink"]);
  });
});
