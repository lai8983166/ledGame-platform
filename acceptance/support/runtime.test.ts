import { mkdtemp, readFile, rm, unlink, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  BoundedLog,
  ManagedProcessRegistry,
  allocateLoopbackPort,
  createOwnedRunDirectory,
  removeOwnedRunDirectory,
  waitForReadiness,
} from "./runtime";

describe("acceptance runtime ownership", () => {
  it("creates a marked run directory and removes only its own path", async () => {
    const base = await mkdtemp(path.join(os.tmpdir(), "ledgame-acceptance-unit-"));
    try {
      const run = await createOwnedRunDirectory(base);
      expect(path.dirname(run)).toBe(path.resolve(base));
      expect(JSON.parse(await readFile(path.join(run, ".acceptance-owned.json"), "utf8"))).toMatchObject({ schemaVersion: 1 });
      await removeOwnedRunDirectory(run, base);
      await expect(readFile(path.join(run, ".acceptance-owned.json"), "utf8")).rejects.toMatchObject({ code: "ENOENT" });
      await expect(removeOwnedRunDirectory(base, base)).rejects.toThrow(/refusing/i);
    } finally {
      await rm(base, { recursive: true, force: true });
    }
  });

  it.runIf(process.platform === "win32")("retries a temporary Windows file lock before removing owned data", async () => {
    const base = await mkdtemp(path.join(os.tmpdir(), "ledgame-acceptance-lock-"));
    const run = await createOwnedRunDirectory(base);
    const lockedPath = path.join(run, "platform.db-wal");
    await writeFile(lockedPath, "isolated test data");
    const locker = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command",
      "$testLockStream = [IO.File]::Open($env:LEDGAME_ACCEPTANCE_LOCK_PATH, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None); try { [Console]::WriteLine('LOCKED'); Start-Sleep -Milliseconds 1000 } finally { $testLockStream.Dispose() }"], {
      env: { ...process.env, LEDGAME_ACCEPTANCE_LOCK_PATH: lockedPath },
      windowsHide: true, stdio: ["ignore", "pipe", "pipe"],
    });
    try {
      await new Promise<void>((resolve, reject) => {
        let output = "";
        let errors = "";
        const timer = setTimeout(() => reject(new Error(`Windows test lock did not open: ${errors}`)), 5_000);
        locker.stdout.on("data", chunk => {
          output += String(chunk);
          if (output.includes("LOCKED")) { clearTimeout(timer); resolve(); }
        });
        locker.stderr.on("data", chunk => { errors += String(chunk); });
        locker.once("error", error => { clearTimeout(timer); reject(error); });
        locker.once("exit", () => { clearTimeout(timer); if (!output.includes("LOCKED")) reject(new Error(errors)); });
      });
      // 先确认锁真实存在，而不是只验证 rm 的配置字符串。
      await expect(unlink(lockedPath)).rejects.toThrow();
      await removeOwnedRunDirectory(run, base);
      await expect(readFile(lockedPath)).rejects.toMatchObject({ code: "ENOENT" });
    } finally {
      locker.kill();
      await rm(base, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    }
  }, 15_000);
});

describe("acceptance runtime primitives", () => {
  it("allocates currently available loopback ports", async () => {
    const first = await allocateLoopbackPort();
    const second = await allocateLoopbackPort();
    expect(first).toBeGreaterThan(0);
    expect(second).toBeGreaterThan(0);
    expect(first).not.toBe(second);
  });

  it("keeps only the bounded log tail", () => {
    const log = new BoundedLog(3);
    log.append("one\ntwo\n");
    log.append("three\nfour\n");
    expect(log.lines()).toEqual(["two", "three", "four"]);
  });

  it("reports the named readiness timeout without long sleeps", async () => {
    await expect(waitForReadiness({ label: "platform", timeoutMs: 30, intervalMs: 5, probe: async () => false }))
      .rejects.toThrow(/platform.*30ms/i);
  });

  it("stops only registered processes in reverse launch order", async () => {
    const stopped: string[] = [];
    const registry = new ManagedProcessRegistry();
    registry.add({ label: "platform", stop: async () => { stopped.push("platform"); } });
    registry.add({ label: "game", stop: async () => { stopped.push("game"); } });
    await registry.stopAll();
    expect(stopped).toEqual(["game", "platform"]);
    await registry.stopAll();
    expect(stopped).toEqual(["game", "platform"]);
  });
});
