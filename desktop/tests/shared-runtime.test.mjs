import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createProductConfigStore } from "../shared/config-store.cjs";
import { createApiTransport, validateApiRequest } from "../shared/api-transport.cjs";
import { assertPortAvailable, buildHttpBaseUrl, checkHealth, validateHost, validatePort } from "../shared/network.cjs";
import { createManagedProcess } from "../shared/managed-process.cjs";
import { createElectronEnvironment } from "../shared/electron-launcher.cjs";

const temporary = [];
afterEach(async () => {
  await Promise.all(temporary.splice(0).map((item) => fs.rm(item, { recursive: true, force: true })));
});

describe("shared desktop runtime", () => {
  it("removes Electron's Node-only mode before launching a desktop shell", () => {
    const environment = createElectronEnvironment({
      PATH: "C:/Windows/System32",
      ELECTRON_RUN_AS_NODE: "1",
    });

    expect(environment.PATH).toBe("C:/Windows/System32");
    expect(environment).not.toHaveProperty("ELECTRON_RUN_AS_NODE");
  });

  it("uses defaults when config is corrupt and replaces it atomically", async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-config-"));
    temporary.push(directory);
    const store = createProductConfigStore(directory, "product", { port: 8090 });
    await store.ensureDirectories();
    await fs.writeFile(store.configPath, "not json");
    await expect(store.read()).resolves.toEqual({ port: 8090 });
    await store.write({ port: 9010 });
    await expect(store.read()).resolves.toEqual({ port: 9010 });
  });

  it("validates hosts, ports and safe API paths", () => {
    expect(buildHttpBaseUrl(validateHost("192.168.1.10"), validatePort(8090))).toBe("http://192.168.1.10:8090");
    expect(() => validateHost("https://evil.test")).toThrow();
    expect(() => validatePort(80)).toThrow();
    expect(() => validateApiRequest({ path: "https://evil.test/api/a" })).toThrow();
    expect(() => validateApiRequest({ path: "/api/../secret" })).toThrow();
  });

  it("whitelists only the operator attribution header from the renderer", () => {
    expect(validateApiRequest({
      path: "/api/members",
      method: "POST",
      body: "{}",
      headers: { "X-Operator-Id": "42", Authorization: "not-allowed", "X-Arbitrary": "drop-me" },
    }).headers).toEqual({ "content-type": "application/json", "x-operator-id": "42" });
    expect(() => validateApiRequest({
      path: "/api/members", method: "POST", body: "{}", headers: { "X-Operator-Id": "not-a-number" },
    })).toThrowError(expect.objectContaining({ code: "INVALID_OPERATOR_ID" }));
  });

  it("limits response size and supports a bounded health timeout", async () => {
    const oversized = "x".repeat(1024 * 1024 + 1);
    const transport = createApiTransport(() => "http://127.0.0.1:8090", {
      fetchImpl: vi.fn().mockResolvedValue(new Response(oversized)),
    });
    await expect(transport({ path: "/api/health", method: "GET" })).rejects.toMatchObject({ code: "RESPONSE_TOO_LARGE" });
    await expect(checkHealth("http://127.0.0.1:8090", {
      timeoutMs: 5,
      fetchImpl: (_url, { signal }) => new Promise((_resolve, reject) => signal.addEventListener("abort", () => reject(Object.assign(new Error(), { name: "AbortError" })))),
    })).rejects.toMatchObject({ code: "TIMEOUT" });
  });

  it("reports an occupied port without selecting a random replacement", async () => {
    const server = net.createServer();
    await new Promise((resolve) => server.listen(0, "0.0.0.0", resolve));
    const { port } = server.address();
    try { await expect(assertPortAvailable(port)).rejects.toMatchObject({ code: "PORT_IN_USE" }); }
    finally { await new Promise((resolve) => server.close(resolve)); }
  });

  it("rotates a bounded log and only stops the child it owns", async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), "ledgame-process-"));
    temporary.push(directory);
    const logPath = path.join(directory, "server.log");
    await fs.writeFile(logPath, "old log exceeds limit");
    const managed = createManagedProcess({ maxLogBytes: 4 });
    managed.start(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { logPath });
    expect(managed.running).toBe(true);
    await managed.stop(1000);
    expect(managed.running).toBe(false);
    await expect(fs.readFile(`${logPath}.1`, "utf8")).resolves.toContain("old log");
  });
});
