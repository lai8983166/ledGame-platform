import { mkdtemp, readFile, rm } from "node:fs/promises";
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
