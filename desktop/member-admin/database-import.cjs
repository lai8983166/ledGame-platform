const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

function sha256(filePath) {
  const hash = crypto.createHash("sha256");
  const fd = fs.openSync(filePath, "r");
  try {
    const buffer = Buffer.allocUnsafe(64 * 1024);
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, count));
  } finally {
    fs.closeSync(fd);
  }
  return hash.digest("hex");
}

function statePath(databasePath) {
  return path.join(path.dirname(databasePath), "database-import-state.json");
}

function writeState(databasePath, value) {
  const target = statePath(databasePath);
  const temporary = `${target}.writing`;
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2), "utf8");
  fs.renameSync(temporary, target);
}

function readState(databasePath) {
  try { return JSON.parse(fs.readFileSync(statePath(databasePath), "utf8")); }
  catch { return null; }
}

function moveIfExists(source, target) {
  if (!fs.existsSync(source)) return;
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.renameSync(source, target);
}

function restoreRollback(databasePath, state) {
  if (!state?.rollbackDirectory) return false;
  const rollbackDirectory = path.resolve(state.rollbackDirectory);
  const dataDirectory = path.dirname(path.resolve(databasePath));
  if (path.dirname(rollbackDirectory) !== path.join(dataDirectory, "pre-import")) {
    throw new Error("INVALID_IMPORT_ROLLBACK_PATH");
  }
  for (const suffix of ["", "-wal", "-shm"]) fs.rmSync(`${databasePath}${suffix}`, { force: true });
  for (const suffix of ["", "-wal", "-shm"]) {
    moveIfExists(path.join(rollbackDirectory, `platform.db${suffix}`), `${databasePath}${suffix}`);
  }
  fs.rmSync(statePath(databasePath), { force: true });
  return true;
}

function recoverInterruptedImport(databasePath) {
  const state = readState(databasePath);
  if (!state) return { recovered: false };
  if (state.phase === "PREPARED") {
    const rollbackDatabase = state.rollbackDirectory
      ? path.join(path.resolve(state.rollbackDirectory), "platform.db") : "";
    if (rollbackDatabase && fs.existsSync(rollbackDatabase)) {
      return { recovered: restoreRollback(databasePath, state), phase: state.phase };
    }
  }
  if (["BACKED_UP", "REPLACED"].includes(state.phase)) {
    return { recovered: restoreRollback(databasePath, state), phase: state.phase };
  }
  fs.rmSync(statePath(databasePath), { force: true });
  return { recovered: false, phase: state.phase };
}

function replaceDatabase(databasePath, manifest, now = new Date()) {
  const source = path.resolve(String(manifest?.preparedDatabasePath || ""));
  const expectedHash = String(manifest?.sha256 || "").toLowerCase();
  if (!fs.existsSync(source) || !/^[a-f0-9]{64}$/.test(expectedHash) || sha256(source) !== expectedHash) {
    throw new Error("IMPORT_PREPARED_HASH_MISMATCH");
  }
  const dataDirectory = path.dirname(path.resolve(databasePath));
  const stamp = now.toISOString().replace(/[:.]/g, "-");
  const rollbackDirectory = path.join(dataDirectory, "pre-import", stamp);
  const staging = path.join(dataDirectory, `platform.db.importing-${process.pid}-${Date.now()}`);
  const state = { phase: "PREPARED", rollbackDirectory, staging, preparedDatabasePath: source, expectedHash };
  fs.mkdirSync(rollbackDirectory, { recursive: true });
  writeState(databasePath, state);
  try {
    for (const suffix of ["", "-wal", "-shm"]) {
      moveIfExists(`${databasePath}${suffix}`, path.join(rollbackDirectory, `platform.db${suffix}`));
    }
    state.phase = "BACKED_UP";
    writeState(databasePath, state);
    fs.copyFileSync(source, staging);
    if (sha256(staging) !== expectedHash) throw new Error("IMPORT_STAGING_HASH_MISMATCH");
    fs.renameSync(staging, databasePath);
    state.phase = "REPLACED";
    writeState(databasePath, state);
    return state;
  } catch (error) {
    fs.rmSync(staging, { force: true });
    restoreRollback(databasePath, state);
    throw error;
  }
}

function markImportVerified(databasePath) {
  fs.rmSync(statePath(databasePath), { force: true });
}

module.exports = {
  markImportVerified,
  readState,
  recoverInterruptedImport,
  replaceDatabase,
  restoreRollback,
  sha256,
};
