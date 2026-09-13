#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

function args(argv) {
  const result = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const value = argv[i];
    if (value.startsWith("--")) result[value.slice(2)] = argv[++i];
    else result._.push(value);
  }
  return result;
}

function readJson(file) { return JSON.parse(fs.readFileSync(path.resolve(file), "utf8")); }
function writeJson(file, value) {
  const target = path.resolve(file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.writing-${process.pid}`;
  fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  fs.renameSync(temporary, target);
}
function pem(type, data) {
  const body = Buffer.from(data).toString("base64").match(/.{1,64}/g)?.join("\n") ?? "";
  return `-----BEGIN ${type}-----\n${body}\n-----END ${type}-----\n`;
}
function required(options, names) {
  for (const name of names) if (!options[name]) throw new Error(`MISSING_${name.toUpperCase()}`);
}

function generateKeys(options) {
  required(options, ["public-output", "private-output"]);
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "der" },
    privateKeyEncoding: { type: "pkcs8", format: "der" },
  });
  const privatePath = path.resolve(options["private-output"]);
  fs.mkdirSync(path.dirname(privatePath), { recursive: true });
  fs.writeFileSync(privatePath, pem("PRIVATE KEY", privateKey), { encoding: "utf8", mode: 0o600 });
  writeText(options["public-output"], pem("PUBLIC KEY", publicKey));
  console.log(`public key written to ${path.resolve(options["public-output"])}`);
  console.log(`private key written outside the customer package to ${privatePath}`);
}

function writeText(file, value) {
  const target = path.resolve(file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.writing-${process.pid}`;
  fs.writeFileSync(temporary, value, "utf8");
  fs.renameSync(temporary, target);
}

function issueResponse(options) {
  required(options, ["request", "envelope", "private-key", "output"]);
  const request = readJson(options.request);
  const envelope = readJson(options.envelope);
  if (request.format !== "ledgame-database-recovery-request-v1"
      || envelope.format !== "ledgame-factory-recovery-key-v1"
      || envelope.algorithm !== "RSA-OAEP-SHA256") {
    throw new Error("RECOVERY_MATERIAL_FORMAT_INVALID");
  }
  if (!request.requestId || !request.recoveryKeyId || !request.instanceId || !request.keyId
      || !request.temporaryPublicKey || !Number.isInteger(request.revision) || request.revision < 0
      || !request.expiresAt) {
    throw new Error(`RECOVERY_REQUEST_INVALID requestId=${request.requestId || "unknown"}`);
  }
  if (request.recoveryKeyId !== envelope.recoveryKeyId || request.keyId !== envelope.keyId) {
    throw new Error(`RECOVERY_MATERIAL_MISMATCH requestId=${request.requestId}`);
  }
  const requestExpires = Date.parse(request.expiresAt);
  if (!Number.isFinite(requestExpires)) throw new Error(`RECOVERY_REQUEST_INVALID requestId=${request.requestId}`);
  if (requestExpires <= Date.now()) throw new Error(`RECOVERY_REQUEST_EXPIRED requestId=${request.requestId}`);
  const privateKey = crypto.createPrivateKey(fs.readFileSync(path.resolve(options["private-key"]), "utf8"));
  const storeKey = crypto.privateDecrypt({ key: privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: "sha256" }, Buffer.from(envelope.wrappedKey, "base64url"));
  const computedKeyId = crypto.createHash("sha256").update(storeKey).digest("hex").slice(0, 24);
  if (computedKeyId !== request.keyId) throw new Error(`RECOVERY_KEY_ID_MISMATCH requestId=${request.requestId}`);
  const temporaryPublicKey = crypto.createPublicKey(request.temporaryPublicKey);
  const wrappedKey = crypto.publicEncrypt({ key: temporaryPublicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: "sha256" }, storeKey).toString("base64url");
  const expiresAt = new Date(Math.min(requestExpires, Date.now() + 30 * 60 * 1000)).toISOString();
  const response = { format: "ledgame-database-recovery-response-v1", requestId: request.requestId,
    wrappedKey, expiresAt };
  writeJson(options.output, response);
  console.log(`response written for request ${request.requestId}`);
}

try {
  const options = args(process.argv.slice(2));
  const command = options._[0];
  if (command === "generate-keys") generateKeys(options);
  else if (command === "issue-response") issueResponse(options);
  else throw new Error("USAGE: generate-keys --public-output ... --private-output ... | issue-response --request ... --envelope ... --private-key ... --output ...");
} catch (error) {
  console.error(String(error?.message || error));
  process.exitCode = 1;
}
