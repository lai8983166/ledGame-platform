const fs = require("node:fs");
const path = require("node:path");

const phase = process.argv[2];
const databasePath = path.resolve(process.argv[3]);
const preparedDatabasePath = path.resolve(process.argv[4]);
const { replaceDatabase, sha256 } = require("../../member-admin/database-import.cjs");

const originalRename = fs.renameSync;
fs.renameSync = function crashAwareRename(source, target) {
  originalRename(source, target);
  const resolvedTarget = path.resolve(target);
  if (resolvedTarget.endsWith("database-import-state.json")) {
    const state = JSON.parse(fs.readFileSync(resolvedTarget, "utf8"));
    if (phase === "ROLLBACK_SAVED" && state.phase === "BACKED_UP") process.kill(process.pid, "SIGKILL");
  }
  if (phase === "DATABASE_PUBLISHED"
      && resolvedTarget === databasePath
      && String(source).includes("platform.db.importing-")) {
    process.kill(process.pid, "SIGKILL");
  }
};

replaceDatabase(databasePath, {
  preparedDatabasePath,
  sha256: sha256(preparedDatabasePath),
});

if (phase === "WAITING_FOR_VERIFICATION") process.kill(process.pid, "SIGKILL");
throw new Error(`unknown crash phase: ${phase}`);
