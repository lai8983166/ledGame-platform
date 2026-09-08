const fs = require("node:fs");
const path = require("node:path");

const DATASETS = Object.freeze({
  members: { path: "/api/exports/members.csv", filename: "会员数据" },
  "wristband-charges": { path: "/api/exports/wristband-charges.csv", filename: "充值交易" },
  "game-plays": { path: "/api/exports/game-plays.csv", filename: "游玩记录" },
});

function resolveDataset(key) {
  const dataset = DATASETS[String(key || "")];
  if (!dataset) throw Object.assign(new Error("不支持的导出数据类型"), { code: "INVALID_EXPORT_DATASET" });
  return dataset;
}

function timestampForFilename(now = new Date()) {
  return now.toISOString().slice(0, 19).replace(/[T:]/g, "-");
}

function ensureUtf8Bom(body) {
  const content = String(body ?? "").replace(/^\uFEFF/, "");
  return Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(content, "utf8")]);
}

function writeFileAtomically(targetPath, body, fileSystem = fs) {
  const resolved = path.resolve(targetPath);
  const temporary = path.join(path.dirname(resolved), `.${path.basename(resolved)}.${process.pid}.${Date.now()}.tmp`);
  try {
    fileSystem.writeFileSync(temporary, ensureUtf8Bom(body), { flag: "wx" });
    fileSystem.renameSync(temporary, resolved);
  } catch (error) {
    try { if (fileSystem.existsSync(temporary)) fileSystem.unlinkSync(temporary); } catch {}
    throw error;
  }
}

async function exportDataset({ key, operatorId, window, dialog, transport, now = new Date(), fileSystem = fs }) {
  const dataset = resolveDataset(key);
  if (!Number.isSafeInteger(operatorId) || operatorId <= 0) throw new Error("请先登录操作账号");
  const result = await dialog.showSaveDialog(window, {
    title: `导出${dataset.filename}`,
    defaultPath: `${dataset.filename}-${timestampForFilename(now)}.csv`,
    filters: [{ name: "CSV 表格", extensions: ["csv"] }],
  });
  window?.show?.(); window?.focus?.(); window?.webContents?.focus?.();
  if (result.canceled || !result.filePath) return { canceled: true };
  const response = await transport({ path: dataset.path, method: "GET", headers: { "X-Operator-Id": operatorId } });
  if (response.status < 200 || response.status >= 300) {
    let message = `导出请求失败（${response.status}）`;
    try { message = JSON.parse(response.body)?.message || message; } catch {}
    throw new Error(message);
  }
  writeFileAtomically(result.filePath, response.body, fileSystem);
  return { canceled: false, filePath: result.filePath };
}

module.exports = { DATASETS, ensureUtf8Bom, exportDataset, resolveDataset, timestampForFilename, writeFileAtomically };
