import http from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { PlatformClient } from "../src/platform-client.js";

const servers: http.Server[] = [];

async function serve(handler: http.RequestListener): Promise<string> {
  const server = http.createServer(handler);
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("server address unavailable");
  return `http://127.0.0.1:${address.port}`;
}

afterEach(async () => Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve())))));

describe("PlatformClient", () => {
  it("uses the existing method, path and JSON body contracts", async () => {
    let received = "";
    const baseUrl = await serve((request, response) => {
      request.setEncoding("utf8");
      request.on("data", (chunk) => { received += chunk; });
      request.on("end", () => {
        response.writeHead(201, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ id: 7, method: request.method, url: request.url }));
      });
    });
    const result = await new PlatformClient(baseUrl, 1000).step("create", "POST", "/api/members", { phone: "13800138000" });
    expect(result).toMatchObject({ kind: "http", status: 201, response: { id: 7, method: "POST", url: "/api/members" } });
    expect(JSON.parse(received)).toEqual({ phone: "13800138000" });
  });

  it("does not retry 5xx and classifies timeout and connection failure", async () => {
    let calls = 0;
    const baseUrl = await serve((_request, response) => { calls += 1; response.writeHead(503); response.end("busy"); });
    expect(await new PlatformClient(baseUrl, 1000).step("once", "GET", "/api/health")).toMatchObject({ kind: "http", status: 503 });
    expect(calls).toBe(1);
    const timeoutUrl = await serve(() => {});
    expect((await new PlatformClient(timeoutUrl, 100).step("timeout", "GET", "/wait")).kind).toBe("timeout");
    expect((await new PlatformClient("http://127.0.0.1:1", 100).step("network", "GET", "/api/health")).kind).toBe("network");
  });
});
