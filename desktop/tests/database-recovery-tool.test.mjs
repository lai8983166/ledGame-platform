import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = path.resolve(fileURLToPath(new URL("../..", import.meta.url)));
const tool = path.join(root, "scripts", "database-recovery-tool.mjs");

test("vendor recovery tool unwraps and rewraps only the store key", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ledgame-recovery-"));
  const publicPath = path.join(directory, "public.pem");
  const privatePath = path.join(directory, "private.pem");
  const requestPath = path.join(directory, "request.json");
  const envelopePath = path.join(directory, "factory-key-envelope.json");
  const responsePath = path.join(directory, "response.json");
  try {
    let result = spawnSync(process.execPath, [tool, "generate-keys", "--public-output", publicPath, "--private-output", privatePath], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    const vendorPublic = crypto.createPublicKey(fs.readFileSync(publicPath));
    const vendorPrivate = crypto.createPrivateKey(fs.readFileSync(privatePath));
    const temporary = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
    const storeKey = crypto.randomBytes(32);
    const keyId = crypto.createHash("sha256").update(storeKey).digest("hex").slice(0, 24);
    const envelope = { format: "ledgame-factory-recovery-key-v1", algorithm: "RSA-OAEP-SHA256",
      recoveryKeyId: "factory-recovery-v1", keyId,
      wrappedKey: crypto.publicEncrypt({ key: vendorPublic, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, storeKey).toString("base64url") };
    const temporaryPublic = temporary.publicKey.export({ type: "spki", format: "pem" });
    const request = { format: "ledgame-database-recovery-request-v1", requestId: "11111111-1111-4111-8111-111111111111",
      recoveryKeyId: "factory-recovery-v1", instanceId: "instance", revision: 7, keyId,
      databaseSha256: "a".repeat(64), metadataSha256: "b".repeat(64), recoveryEnvelopeSha256: "c".repeat(64),
      temporaryPublicKey: temporaryPublic, createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString() };
    fs.writeFileSync(requestPath, JSON.stringify(request));
    fs.writeFileSync(envelopePath, JSON.stringify(envelope));
    result = spawnSync(process.execPath, [tool, "issue-response", "--request", requestPath, "--envelope", envelopePath, "--private-key", privatePath, "--output", responsePath], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    const response = JSON.parse(fs.readFileSync(responsePath, "utf8"));
    const unwrapped = crypto.privateDecrypt({ key: temporary.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, Buffer.from(response.wrappedKey, "base64url"));
    assert.deepEqual(unwrapped, storeKey);
    assert.equal(response.requestId, request.requestId);
    assert.deepEqual(Object.keys(response).sort(), ["expiresAt", "format", "requestId", "wrappedKey"]);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
