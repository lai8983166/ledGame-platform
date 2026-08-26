const { spawn } = require("node:child_process");

function createElectronEnvironment(source = process.env) {
  const environment = { ...source };
  delete environment.ELECTRON_RUN_AS_NODE;
  return environment;
}

function launchElectron(args = process.argv.slice(2), options = {}) {
  if (args.length === 0) {
    throw new Error("Electron entry file is required");
  }

  const electronExecutable = options.electronExecutable || require("electron");
  const child = spawn(electronExecutable, args, {
    cwd: options.cwd || process.cwd(),
    env: createElectronEnvironment(options.env || process.env),
    stdio: "inherit",
    windowsHide: false,
  });

  const stopChild = () => {
    if (!child.killed) child.kill();
  };
  process.once("SIGINT", stopChild);
  process.once("SIGTERM", stopChild);
  child.once("error", (error) => {
    console.error(`Unable to launch Electron: ${error.message}`);
    process.exitCode = 1;
  });
  child.once("exit", (code) => {
    process.exitCode = code ?? 1;
  });
  return child;
}

if (require.main === module) {
  launchElectron();
}

module.exports = { createElectronEnvironment, launchElectron };
