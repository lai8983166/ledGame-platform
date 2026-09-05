import fs from "node:fs";
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  markImportVerified,
  readState,
  recoverInterruptedImport,
  replaceDatabase,
  restoreRollback,
  sha256,
} from "../member-admin/database-import.cjs";

const roots = [];
afterEach(() => {
  for (const root of roots.splice(0)) fs.rmSync(root, { recursive: true, force: true });
});

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ledgame-import-"));
  roots.push(root);
  const databasePath = path.join(root, "data", "platform.db");
  const preparedDatabasePath = path.join(root, "data", "import-staging", "candidate.db");
  fs.mkdirSync(path.dirname(databasePath), { recursive: true });
  fs.mkdirSync(path.dirname(preparedDatabasePath), { recursive: true });
  fs.writeFileSync(databasePath, "old-database");
  fs.writeFileSync(preparedDatabasePath, "new-database");
  return { databasePath, preparedDatabasePath };
}

describe("member admin atomic database import", () => {
  it("keeps a pre-import copy, verifies the staged hash and publishes the candidate", () => {
    const files = fixture();
    const state = replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
    });

    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("new-database");
    expect(fs.readFileSync(path.join(state.rollbackDirectory, "platform.db"), "utf8")).toBe("old-database");
    expect(readState(files.databasePath)?.phase).toBe("REPLACED");
    markImportVerified(files.databasePath);
    expect(readState(files.databasePath)).toBeNull();
  });

  it("restores the previous database after replacement failure or interrupted verification", () => {
    const files = fixture();
    const state = replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
    });
    expect(restoreRollback(files.databasePath, state)).toBe(true);
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");

    const second = replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
    });
    expect(second.phase).toBe("REPLACED");
    expect(recoverInterruptedImport(files.databasePath)).toMatchObject({ recovered: true, phase: "REPLACED" });
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
  });

  it("does not touch the main database when the prepared hash is invalid", () => {
    const files = fixture();
    expect(() => replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: "0".repeat(64),
    })).toThrow("IMPORT_PREPARED_HASH_MISMATCH");
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
  });

  it("recovers when power is lost after the main file moved but before the phase advanced", () => {
    const files = fixture();
    const rollbackDirectory = path.join(path.dirname(files.databasePath), "pre-import", "interrupted");
    fs.mkdirSync(rollbackDirectory, { recursive: true });
    fs.renameSync(files.databasePath, path.join(rollbackDirectory, "platform.db"));
    fs.writeFileSync(path.join(path.dirname(files.databasePath), "database-import-state.json"), JSON.stringify({
      phase: "PREPARED",
      rollbackDirectory,
    }));

    expect(recoverInterruptedImport(files.databasePath)).toMatchObject({ recovered: true, phase: "PREPARED" });
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
  });

  for (const phase of ["ROLLBACK_SAVED", "DATABASE_PUBLISHED", "WAITING_FOR_VERIFICATION"]) {
    it(`recovers a complete old database after a real child process is killed at ${phase}`, () => {
      const files = fixture();
      const child = spawnSync(process.execPath, [
        path.join(import.meta.dirname, "fixtures/import-crash-child.cjs"),
        phase,
        files.databasePath,
        files.preparedDatabasePath,
      ], { stdio: "ignore", windowsHide: true, timeout: 10_000 });

      expect(child.status).not.toBe(0);
      expect(readState(files.databasePath)?.phase).toMatch(/PREPARED|BACKED_UP|REPLACED/);
      expect(recoverInterruptedImport(files.databasePath)).toMatchObject({ recovered: true });
      expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
      expect(readState(files.databasePath)).toBeNull();
      expect(fs.readdirSync(path.dirname(files.databasePath)))
        .not.toContain(expect.stringMatching(/^platform\.db\.importing-/));
    });
  }
});
