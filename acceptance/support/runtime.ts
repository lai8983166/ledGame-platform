import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import net from "node:net";
import path from "node:path";

const OWNERSHIP_FILE = ".acceptance-owned.json";
const allocatedPorts = new Set<number>();

function isDirectChild(target: string, parent: string): boolean {
  return path.dirname(path.resolve(target)) === path.resolve(parent);
}

export async function createOwnedRunDirectory(baseDirectory: string): Promise<string> {
  const base = path.resolve(baseDirectory);
  await mkdir(base, { recursive: true });
  const runDirectory = await mkdtemp(path.join(base, "run-"));
  await writeFile(path.join(runDirectory, OWNERSHIP_FILE), `${JSON.stringify({
    schemaVersion: 1,
    owner: "ledgame-store-acceptance",
    createdAt: new Date().toISOString(),
  }, null, 2)}\n`, "utf8");
  return runDirectory;
}

export async function removeOwnedRunDirectory(runDirectory: string, baseDirectory: string): Promise<void> {
  const run = path.resolve(runDirectory);
  const base = path.resolve(baseDirectory);
  if (!isDirectChild(run, base) || run === base) {
    throw new Error(`Refusing to remove non-owned acceptance path: ${run}`);
  }
  let marker: { owner?: string; schemaVersion?: number };
  try {
    marker = JSON.parse(await readFile(path.join(run, OWNERSHIP_FILE), "utf8"));
  } catch (error) {
    throw new Error(`Refusing to remove unmarked acceptance path: ${run}`, { cause: error });
  }
  if (marker.owner !== "ledgame-store-acceptance" || marker.schemaVersion !== 1) {
    throw new Error(`Refusing to remove acceptance path with invalid ownership marker: ${run}`);
  }
  await rm(run, { recursive: true, force: true });
}

async function listenOnRandomPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

export async function allocateLoopbackPort(): Promise<number> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const port = await listenOnRandomPort();
    if (port > 0 && !allocatedPorts.has(port)) {
      allocatedPorts.add(port);
      return port;
    }
  }
  throw new Error("Unable to allocate a unique loopback port");
}

export class BoundedLog {
  readonly #capacity: number;
  readonly #lines: string[] = [];
  #remainder = "";

  constructor(capacity = 400) {
    this.#capacity = Math.max(1, Math.floor(capacity));
  }

  append(chunk: string | Buffer): void {
    const parts = `${this.#remainder}${String(chunk).replaceAll("\r\n", "\n")}`.split("\n");
    this.#remainder = parts.pop() ?? "";
    for (const line of parts) this.#push(line);
  }

  #push(line: string): void {
    if (!line && !this.#lines.length) return;
    this.#lines.push(line);
    if (this.#lines.length > this.#capacity) this.#lines.splice(0, this.#lines.length - this.#capacity);
  }

  lines(): string[] {
    const result = [...this.#lines];
    if (this.#remainder) result.push(this.#remainder);
    return result.slice(-this.#capacity);
  }

  text(): string {
    return this.lines().join("\n");
  }
}

export async function waitForReadiness({
  label,
  probe,
  timeoutMs = 30_000,
  intervalMs = 200,
  hasExited = () => false,
}: {
  label: string;
  probe: () => Promise<boolean>;
  timeoutMs?: number;
  intervalMs?: number;
  hasExited?: () => boolean;
}): Promise<void> {
  const startedAt = Date.now();
  let lastError: unknown;
  while (Date.now() - startedAt < timeoutMs) {
    if (hasExited()) throw new Error(`${label} exited before becoming ready`);
    try {
      if (await probe()) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  const detail = lastError instanceof Error ? `: ${lastError.message}` : "";
  throw new Error(`${label} was not ready within ${timeoutMs}ms${detail}`);
}

export type ManagedProcess = { label: string; stop: () => Promise<void> };

export type ManagedChildProcess = ManagedProcess & {
  child: ChildProcess;
  log: BoundedLog;
  hasExited: () => boolean;
};

async function waitForExit(child: ChildProcess, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return true;
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.off("exit", onExit);
      resolve(false);
    }, timeoutMs);
    const onExit = () => {
      clearTimeout(timer);
      resolve(true);
    };
    child.once("exit", onExit);
  });
}

async function terminateProcessTree(child: ChildProcess): Promise<void> {
  const pid = child.pid;
  if (!pid || child.exitCode !== null || child.signalCode !== null) return;
  if (process.platform === "win32") {
    await new Promise<void>((resolve) => {
      const killer = spawn("taskkill.exe", ["/PID", String(pid), "/T"], { windowsHide: true, stdio: "ignore" });
      killer.once("exit", () => resolve());
      killer.once("error", () => resolve());
    });
    if (await waitForExit(child, 3_000)) return;
    await new Promise<void>((resolve) => {
      const killer = spawn("taskkill.exe", ["/PID", String(pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
      killer.once("exit", () => resolve());
      killer.once("error", () => resolve());
    });
    await waitForExit(child, 3_000);
    return;
  }
  try { process.kill(-pid, "SIGTERM"); } catch { child.kill("SIGTERM"); }
  if (await waitForExit(child, 3_000)) return;
  try { process.kill(-pid, "SIGKILL"); } catch { child.kill("SIGKILL"); }
  await waitForExit(child, 3_000);
}

export function spawnManagedProcess({
  label,
  command,
  args,
  cwd,
  env = {},
  logFile,
  logCapacity = 500,
}: {
  label: string;
  command: string;
  args: string[];
  cwd: string;
  env?: NodeJS.ProcessEnv;
  logFile: string;
  logCapacity?: number;
}): ManagedChildProcess {
  const log = new BoundedLog(logCapacity);
  const stream = createWriteStream(logFile, { flags: "a" });
  const windowsBatch = process.platform === "win32" && !command.toLowerCase().endsWith(".exe");
  const spawnCommand = windowsBatch ? (process.env.ComSpec || "cmd.exe") : command;
  const spawnArgs = windowsBatch
    ? ["/d", "/s", "/c", "call", command.toLowerCase().endsWith(".cmd") ? command : `${command}.cmd`, ...args]
    : args;
  const child = spawn(spawnCommand, spawnArgs, {
    cwd,
    env: { ...process.env, ...env },
    windowsHide: true,
    detached: process.platform !== "win32",
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const capture = (source: "stdout" | "stderr", chunk: Buffer) => {
    const prefixed = String(chunk).split(/(?<=\n)/).map((part) => part ? `[${label}:${source}] ${part}` : "").join("");
    log.append(prefixed);
    stream.write(prefixed);
  };
  child.stdout?.on("data", (chunk: Buffer) => capture("stdout", chunk));
  child.stderr?.on("data", (chunk: Buffer) => capture("stderr", chunk));
  child.once("error", (error) => {
    const message = `[${label}:spawn] ${error.stack || error.message}\n`;
    log.append(message);
    stream.write(message);
  });
  child.once("close", () => stream.end());

  return {
    label,
    child,
    log,
    hasExited: () => child.exitCode !== null || child.signalCode !== null,
    stop: async () => terminateProcessTree(child),
  };
}

export class ManagedProcessRegistry {
  readonly #processes: ManagedProcess[] = [];
  #stopped = false;

  add(process: ManagedProcess): void {
    if (this.#stopped) throw new Error("Cannot register a process after cleanup");
    this.#processes.push(process);
  }

  async stopAll(): Promise<void> {
    if (this.#stopped) return;
    this.#stopped = true;
    const failures: Error[] = [];
    for (const process of [...this.#processes].reverse()) {
      try {
        await process.stop();
      } catch (error) {
        failures.push(new Error(`Failed to stop ${process.label}`, { cause: error }));
      }
    }
    if (failures.length) throw new AggregateError(failures, "Acceptance process cleanup failed");
  }
}
