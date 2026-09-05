import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import type { ChildProcess } from "node:child_process";
import { afterEach, describe, expect, it } from "vitest";
import { createCenterRunPaths, preflightCenter, startCenter } from "../src/center.js";
import { SAFETY_CONFIRMATION, type CenterConfig } from "../src/types.js";

const roots: string[] = [];

async function fixture(): Promise<{ root: string; executable: string; config: CenterConfig }> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-concurrency-center-"));
  roots.push(root);
  const executable = path.join(root, "LED Game Member Admin.exe");
  await fs.writeFile(executable, "fixture");
  return {
    root,
    executable,
    config: {
      runId: "CONC-20260904-001",
      memberAdminExecutable: executable,
      testRoot: path.join(root, "runs"),
      lanHost: "192.168.124.10",
      testPort: 18090,
      normalPlatformUrl: "http://127.0.0.1:8090",
      startupTimeoutMs: 2_000,
      safetyConfirmation: SAFETY_CONFIRMATION,
    },
  };
}

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true })));
});

describe("isolated packaged center launch", () => {
  it("derives every mutable center path from the run id", async () => {
    const { config } = await fixture();
    const paths = createCenterRunPaths(config);
    expect(paths.userData).toBe(path.join(config.testRoot, config.runId, "member-admin"));
    expect(paths.backupRoot).toBe(path.join(config.testRoot, config.runId, "backup"));
    expect(paths.centerLog).toContain(path.join(config.runId, "member-admin", "logs", "server.log"));
    expect(path.relative(config.testRoot, paths.userData)).toBe(path.join(config.runId, "member-admin"));
  });

  it("rejects a missing executable, running normal service and occupied fixed port", async () => {
    const { config } = await fixture();
    await expect(preflightCenter({ ...config, memberAdminExecutable: path.join(config.testRoot, "missing.exe") }, {
      fetchImpl: async () => { throw new Error("offline"); },
      portAvailable: async () => true,
    })).rejects.toThrow("未找到会员管理端程序");
    await expect(preflightCenter(config, {
      fetchImpl: async () => new Response("ok", { status: 200 }),
      portAvailable: async () => true,
    })).rejects.toThrow("正常会员管理端仍在运行");
    await expect(preflightCenter(config, {
      fetchImpl: async () => { throw new Error("offline"); },
      portAvailable: async () => false,
    })).rejects.toThrow("测试端口 18090 已被占用");
  });

  it("starts the selected executable with isolated environment and writes connection info", async () => {
    const { config } = await fixture();
    let capturedEnv: NodeJS.ProcessEnv | undefined;
    let healthCalls = 0;
    const child = { exitCode: null } as ChildProcess;
    const connection = await startCenter(config, {
      portAvailable: async () => true,
      fetchImpl: async (input) => {
        const url = String(input);
        if (url.includes(":8090")) throw new Error("normal service is closed");
        healthCalls += 1;
        return new Response(JSON.stringify(url.endsWith("/api/system/startup-status") ? { state: "READY_PROTECTED" } : { ok: true }), { status: 200 });
      },
      spawnProcess: (_executable, env) => { capturedEnv = env; return child; },
      now: () => new Date("2026-09-04T08:00:00.000Z"),
    });
    expect(healthCalls).toBeGreaterThan(0);
    expect(capturedEnv).toMatchObject({
      LEDGAME_PLATFORM_PORT: "18090",
      LEDGAME_DATABASE_BACKUP_ENVIRONMENT: "TEST",
      LEDGAME_CONCURRENCY_TEST_RUN_ID: config.runId,
    });
    expect(capturedEnv?.LEDGAME_USER_DATA).toContain(config.runId);
    expect(capturedEnv?.LEDGAME_DATABASE_BACKUP_ROOT).toContain(config.runId);
    expect(connection.platformBaseUrl).toBe("http://192.168.124.10:18090");
    expect(JSON.parse(await fs.readFile(path.join(connection.runRoot, "connection.json"), "utf8"))).toEqual(connection);
  });
});
