const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

function assertActivationPublicKey(root) {
  const file = path.join(root, "server/src/main/resources/activation-public.pem");
  if (!fs.existsSync(file)) throw new Error("缺少厂家激活公钥：请将 activation-public.pem 放入 server/src/main/resources 后再打包；不要复制私钥。");
  const pem = fs.readFileSync(file, "utf8");
  if (pem.includes("PRIVATE KEY")) throw new Error("禁止将厂家私钥作为公钥打包");
  const key = crypto.createPublicKey(pem);
  if (key.asymmetricKeyType !== "ed25519") throw new Error("厂家激活公钥必须为 Ed25519");
  return pem;
}
function assertNoPrivateKeys(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (["node_modules", "target", ".git", "dist", "release", "test-results"].includes(entry.name)) continue;
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`发布源目录不得包含符号链接：${file}`);
    if (entry.isDirectory()) { assertNoPrivateKeys(file); continue; }
    if (!entry.isFile()) continue;
    if (/private.*\.(pem|key)$|license\.json$/i.test(entry.name)) throw new Error(`发布源目录包含授权秘密或运行授权：${file}`);
    const content = fs.readFileSync(file);
    if (/(?:^|\n)-----BEGIN (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----/.test(content.toString("utf8"))) throw new Error(`发布源文件包含私钥：${file}`);
  }
}
module.exports = { assertActivationPublicKey, assertNoPrivateKeys };
