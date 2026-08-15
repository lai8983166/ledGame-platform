const fs = require("node:fs/promises");
const path = require("node:path");

function createProductConfigStore(userDataPath, productName, defaults) {
  const root = path.win32.normalize(userDataPath);
  const configPath = path.win32.join(root, `${productName}.json`);
  const dataDirectory = path.win32.join(root, "data");
  const logDirectory = path.win32.join(root, "logs");

  return {
    configPath,
    dataPath(name) { return path.win32.join(dataDirectory, name); },
    logPath(name) { return path.win32.join(logDirectory, name); },
    async ensureDirectories() {
      await Promise.all([
        fs.mkdir(path.dirname(configPath), { recursive: true }),
        fs.mkdir(dataDirectory, { recursive: true }),
        fs.mkdir(logDirectory, { recursive: true }),
      ]);
    },
    async read() {
      try {
        const parsed = JSON.parse(await fs.readFile(configPath, "utf8"));
        return { ...defaults, ...parsed };
      } catch {
        return { ...defaults };
      }
    },
    async write(value) {
      await this.ensureDirectories();
      const next = { ...defaults, ...value };
      const temporaryPath = `${configPath}.${process.pid}.tmp`;
      await fs.writeFile(temporaryPath, `${JSON.stringify(next, null, 2)}\n`, "utf8");
      await fs.rename(temporaryPath, configPath);
      return next;
    },
  };
}

module.exports = { createProductConfigStore };
