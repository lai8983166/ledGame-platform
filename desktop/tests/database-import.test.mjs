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
  it("rejects an avatar changed after server preparation before touching the main database", () => {
    const files = fixture();
    const directory = path.join(path.dirname(files.preparedDatabasePath),"avatars");
    const name = "11111111-1111-1111-1111-111111111111.bin";
    fs.mkdirSync(directory,{recursive:true});
    fs.writeFileSync(path.join(directory,name),"tampered");
    const manifest = path.join(path.dirname(files.preparedDatabasePath),"avatars.json");
    fs.writeFileSync(manifest,JSON.stringify({format:"ledgame-avatar-backup-v1",files:[{name,size:8,sha256:"0".repeat(64)}]}));
    expect(() => replaceDatabase(files.databasePath,{
      preparedDatabasePath:files.preparedDatabasePath,sha256:sha256(files.preparedDatabasePath),
      preparedAvatarDirectoryPath:directory,preparedAvatarManifestPath:manifest,avatarManifestSha256:sha256(manifest),
    })).toThrow("IMPORT_AVATAR_HASH_MISMATCH");
    expect(fs.readFileSync(files.databasePath,"utf8")).toBe("old-database");
  });
  it("restores branding with the database and rolls it back when verification fails", () => {
    const files = fixture();
    const data = path.dirname(files.databasePath);
    const branding = path.join(data,"branding");
    const preparedBranding = path.join(data,"import-staging","branding");
    fs.mkdirSync(branding,{ recursive:true });
    fs.mkdirSync(preparedBranding,{ recursive:true });
    fs.writeFileSync(path.join(branding,"app-icon.png"),"old-icon");
    fs.writeFileSync(path.join(preparedBranding,"app-icon.jpg"),"new-icon");
    const state = replaceDatabase(files.databasePath,{
      preparedDatabasePath:files.preparedDatabasePath,sha256:sha256(files.preparedDatabasePath),
      preparedBrandingDirectoryPath:preparedBranding,brandingFileName:"app-icon.jpg",
      brandingFileSha256:sha256(path.join(preparedBranding,"app-icon.jpg")),
    });
    expect(fs.readFileSync(path.join(branding,"app-icon.jpg"),"utf8")).toBe("new-icon");
    restoreRollback(files.databasePath,state);
    expect(fs.readFileSync(path.join(branding,"app-icon.png"),"utf8")).toBe("old-icon");
    expect(fs.existsSync(path.join(branding,"app-icon.jpg"))).toBe(false);
  });

  it("rejects tampered branding before moving the existing database", () => {
    const files = fixture();
    const branding = path.join(path.dirname(files.preparedDatabasePath),"branding");
    fs.mkdirSync(branding,{recursive:true});
    fs.writeFileSync(path.join(branding,"app-icon.png"),"tampered");
    expect(() => replaceDatabase(files.databasePath,{
      preparedDatabasePath:files.preparedDatabasePath,sha256:sha256(files.preparedDatabasePath),
      preparedBrandingDirectoryPath:branding,brandingFileName:"app-icon.png",brandingFileSha256:"0".repeat(64),
    })).toThrow("IMPORT_BRANDING_HASH_MISMATCH");
    expect(fs.readFileSync(files.databasePath,"utf8")).toBe("old-database");
  });
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

  it("atomically replaces and rolls back the DPAPI key envelope with the database", () => {
    const files = fixture();
    const keyPath = path.join(path.dirname(files.databasePath), "security", "data-key.dpapi");
    const preparedKey = path.join(path.dirname(files.preparedDatabasePath), "candidate-key.dpapi");
    fs.mkdirSync(path.dirname(keyPath), { recursive: true });
    fs.writeFileSync(keyPath, "old-protected-key");
    fs.writeFileSync(preparedKey, "new-protected-key");

    const state = replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
      preparedKeyEnvelopePath: preparedKey,
      keyEnvelopeSha256: sha256(preparedKey),
    });

    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("new-database");
    expect(fs.readFileSync(keyPath, "utf8")).toBe("new-protected-key");
    expect(fs.readFileSync(path.join(state.rollbackDirectory, "data-key.dpapi"), "utf8"))
      .toBe("old-protected-key");
    expect(restoreRollback(files.databasePath, state)).toBe(true);
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
    expect(fs.readFileSync(keyPath, "utf8")).toBe("old-protected-key");
  });

  it("replaces and rolls back the encrypted avatar bundle with the database", () => {
    const files = fixture();
    const dataDirectory = path.dirname(files.databasePath);
    const oldAvatars = path.join(dataDirectory, "avatars");
    const preparedAvatars = path.join(path.dirname(files.preparedDatabasePath), "candidate.avatars");
    const avatarName = "11111111-1111-1111-1111-111111111111.bin";
    fs.mkdirSync(oldAvatars, { recursive: true });
    fs.writeFileSync(path.join(oldAvatars, avatarName), "old-avatar");
    fs.mkdirSync(preparedAvatars, { recursive: true });
    fs.writeFileSync(path.join(preparedAvatars, avatarName), "new-avatar");
    const preparedManifest = path.join(path.dirname(files.preparedDatabasePath), "candidate-avatar-manifest.json");
    fs.writeFileSync(preparedManifest, JSON.stringify({ format: "ledgame-avatar-backup-v1", files: [{ name: avatarName, size: 10, sha256: sha256(path.join(preparedAvatars, avatarName)) }] }));

    const state = replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
      preparedAvatarDirectoryPath: preparedAvatars,
      preparedAvatarManifestPath: preparedManifest,
      avatarManifestSha256: sha256(preparedManifest),
    });
    expect(fs.readFileSync(path.join(oldAvatars, avatarName), "utf8")).toBe("new-avatar");
    expect(fs.readFileSync(path.join(state.rollbackDirectory, "avatars", avatarName), "utf8")).toBe("old-avatar");
    expect(restoreRollback(files.databasePath, state)).toBe(true);
    expect(fs.readFileSync(path.join(oldAvatars, avatarName), "utf8")).toBe("old-avatar");
  });

  it("rejects an invalid prepared key envelope hash before touching current data", () => {
    const files = fixture();
    const keyPath = path.join(path.dirname(files.databasePath), "security", "data-key.dpapi");
    const preparedKey = path.join(path.dirname(files.preparedDatabasePath), "candidate-key.dpapi");
    fs.mkdirSync(path.dirname(keyPath), { recursive: true });
    fs.writeFileSync(keyPath, "old-protected-key");
    fs.writeFileSync(preparedKey, "new-protected-key");

    expect(() => replaceDatabase(files.databasePath, {
      preparedDatabasePath: files.preparedDatabasePath,
      sha256: sha256(files.preparedDatabasePath),
      preparedKeyEnvelopePath: preparedKey,
      keyEnvelopeSha256: "0".repeat(64),
    })).toThrow("IMPORT_PREPARED_KEY_HASH_MISMATCH");
    expect(fs.readFileSync(files.databasePath, "utf8")).toBe("old-database");
    expect(fs.readFileSync(keyPath, "utf8")).toBe("old-protected-key");
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
