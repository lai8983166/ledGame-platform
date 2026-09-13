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
  if (state.replaceKeyEnvelope) {
    const keyPath = path.join(dataDirectory, "security", "data-key.dpapi");
    fs.rmSync(keyPath, { force: true });
    moveIfExists(path.join(rollbackDirectory, "data-key.dpapi"), keyPath);
  }
  if (state.replaceAvatars && state.avatarsBackedUp) {
    const avatarPath = path.join(dataDirectory, "avatars");
    fs.rmSync(avatarPath, { recursive: true, force: true });
    moveIfExists(path.join(rollbackDirectory, "avatars"), avatarPath);
    fs.rmSync(path.join(dataDirectory, "avatar-manifest.json"), { force: true });
    moveIfExists(path.join(rollbackDirectory, "avatar-manifest.json"), path.join(dataDirectory, "avatar-manifest.json"));
  }
  if (state.staging) fs.rmSync(state.staging, { force: true });
  if (state.stagingKey) fs.rmSync(state.stagingKey, { force: true });
  fs.rmSync(statePath(databasePath), { force: true });
  fs.rmSync(path.join(dataDirectory, "avatar-manifest.json.importing"), { force: true });
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
  const sourceKeyEnvelope = manifest?.preparedKeyEnvelopePath
    ? path.resolve(String(manifest.preparedKeyEnvelopePath)) : null;
  const expectedKeyHash = String(manifest?.keyEnvelopeSha256 || "").toLowerCase();
  if (sourceKeyEnvelope && (!fs.existsSync(sourceKeyEnvelope)
      || !/^[a-f0-9]{64}$/.test(expectedKeyHash)
      || sha256(sourceKeyEnvelope) !== expectedKeyHash)) {
    throw new Error("IMPORT_PREPARED_KEY_HASH_MISMATCH");
  }
  const sourceAvatars = manifest?.preparedAvatarDirectoryPath
    ? path.resolve(String(manifest.preparedAvatarDirectoryPath)) : null;
  const sourceAvatarManifest = manifest?.preparedAvatarManifestPath
    ? path.resolve(String(manifest.preparedAvatarManifestPath)) : null;
  const expectedAvatarManifestHash = String(manifest?.avatarManifestSha256 || "").toLowerCase();
  if (sourceAvatars && (!fs.existsSync(sourceAvatars) || !fs.statSync(sourceAvatars).isDirectory())) {
    throw new Error("IMPORT_PREPARED_AVATAR_DIRECTORY_MISSING");
  }
  if (sourceAvatarManifest && (!fs.existsSync(sourceAvatarManifest)
      || !/^[a-f0-9]{64}$/.test(expectedAvatarManifestHash)
      || sha256(sourceAvatarManifest) !== expectedAvatarManifestHash)) {
    throw new Error("IMPORT_PREPARED_AVATAR_MANIFEST_MISMATCH");
  }
  const dataDirectory = path.dirname(path.resolve(databasePath));
  const stamp = now.toISOString().replace(/[:.]/g, "-");
  const rollbackDirectory = path.join(dataDirectory, "pre-import", stamp);
  const staging = path.join(dataDirectory, `platform.db.importing-${process.pid}-${Date.now()}`);
  const keyPath = path.join(dataDirectory, "security", "data-key.dpapi");
  const stagingKey = path.join(dataDirectory, `data-key.dpapi.importing-${process.pid}-${Date.now()}`);
  const stagingAvatars = path.join(dataDirectory, `avatars.importing-${process.pid}-${Date.now()}`);
  const state = { phase: "PREPARED", rollbackDirectory, staging,
    preparedDatabasePath: source, expectedHash, replaceKeyEnvelope: Boolean(sourceKeyEnvelope), stagingKey,
    replaceAvatars: Boolean(sourceAvatars), sourceAvatars, sourceAvatarManifest, expectedAvatarManifestHash, stagingAvatars };
  fs.mkdirSync(rollbackDirectory, { recursive: true });
  writeState(databasePath, state);
  try {
    for (const suffix of ["", "-wal", "-shm"]) {
      moveIfExists(`${databasePath}${suffix}`, path.join(rollbackDirectory, `platform.db${suffix}`));
    }
    if (sourceKeyEnvelope) moveIfExists(keyPath, path.join(rollbackDirectory, "data-key.dpapi"));
    if (sourceAvatars) {
      moveIfExists(path.join(dataDirectory, "avatars"), path.join(rollbackDirectory, "avatars"));
      moveIfExists(path.join(dataDirectory, "avatar-manifest.json"), path.join(rollbackDirectory, "avatar-manifest.json"));
      state.avatarsBackedUp = true;
    }
    state.phase = "BACKED_UP";
    writeState(databasePath, state);
    fs.copyFileSync(source, staging);
    if (sha256(staging) !== expectedHash) throw new Error("IMPORT_STAGING_HASH_MISMATCH");
    fs.renameSync(staging, databasePath);
    if (sourceKeyEnvelope) {
      fs.mkdirSync(path.dirname(keyPath), { recursive: true });
      fs.copyFileSync(sourceKeyEnvelope, stagingKey);
      if (sha256(stagingKey) !== expectedKeyHash) throw new Error("IMPORT_STAGING_KEY_HASH_MISMATCH");
      fs.renameSync(stagingKey, keyPath);
    }
    if (sourceAvatars) {
      copyDirectory(sourceAvatars, stagingAvatars);
      if (sourceAvatarManifest) fs.copyFileSync(sourceAvatarManifest, path.join(dataDirectory, "avatar-manifest.json.importing"));
      fs.renameSync(stagingAvatars, path.join(dataDirectory, "avatars"));
      if (sourceAvatarManifest) fs.renameSync(path.join(dataDirectory, "avatar-manifest.json.importing"), path.join(dataDirectory, "avatar-manifest.json"));
    }
    state.phase = "REPLACED";
    writeState(databasePath, state);
    return state;
  } catch (error) {
    fs.rmSync(staging, { force: true });
    fs.rmSync(stagingKey, { force: true });
    fs.rmSync(stagingAvatars, { recursive: true, force: true });
    fs.rmSync(path.join(dataDirectory, "avatar-manifest.json.importing"), { force: true });
    restoreRollback(databasePath, state);
    throw error;
  }
}

function copyDirectory(source, target) {
  fs.mkdirSync(target, { recursive: true });
  for (const name of fs.readdirSync(source)) {
    if (!/^[0-9a-fA-F-]{36}\.bin$/.test(name)) throw new Error("IMPORT_INVALID_AVATAR_FILE");
    const sourceFile = path.join(source, name);
    const targetFile = path.join(target, name);
    if (!fs.statSync(sourceFile).isFile()) throw new Error("IMPORT_INVALID_AVATAR_FILE");
    fs.copyFileSync(sourceFile, targetFile);
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
