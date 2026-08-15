const fs = require("node:fs");
const { spawn } = require("node:child_process");

function createManagedProcess({ maxTailLines = 200, maxLogBytes = 5 * 1024 * 1024 } = {}) {
  let child = null;
  let logStream = null;
  const tail = [];

  function capture(chunk) {
    const text = String(chunk);
    if (logStream) logStream.write(text);
    tail.push(...text.split(/\r?\n/).filter(Boolean));
    if (tail.length > maxTailLines) tail.splice(0, tail.length - maxTailLines);
  }

  return {
    get child() { return child; },
    get running() { return Boolean(child && child.exitCode === null); },
    tail() { return [...tail]; },
    start(executable, args, { env, cwd, logPath, onExit } = {}) {
      if (child && child.exitCode === null) throw new Error("managed process is already running");
      if (logPath && fs.existsSync(logPath) && fs.statSync(logPath).size >= maxLogBytes) {
        const backupPath = `${logPath}.1`;
        fs.rmSync(backupPath, { force: true });
        fs.renameSync(logPath, backupPath);
      }
      logStream = logPath ? fs.createWriteStream(logPath, { flags: "a" }) : null;
      child = spawn(executable, args, { cwd, env: { ...process.env, ...env }, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
      const started = child;
      child.stdout?.on("data", capture);
      child.stderr?.on("data", capture);
      child.once("exit", (code, signal) => {
        logStream?.end();
        logStream = null;
        if (child === started) child = null;
        onExit?.({ code, signal });
      });
      return child;
    },
    async stop(timeoutMs = 5000) {
      const target = child;
      if (!target || target.exitCode !== null) return;
      await new Promise((resolve) => {
        const timer = setTimeout(() => {
          if (target.exitCode === null) {
            if (process.platform === "win32") {
              spawn("taskkill", ["/pid", String(target.pid), "/t", "/f"], { windowsHide: true, stdio: "ignore" });
            } else target.kill("SIGKILL");
          }
          resolve();
        }, timeoutMs);
        target.once("exit", () => { clearTimeout(timer); resolve(); });
        target.kill("SIGTERM");
      });
      if (child === target) child = null;
    },
  };
}

module.exports = { createManagedProcess };
