import { describe, expect, it } from "vitest";
import { DEFAULT_PLATFORM_BASE_URL, resolvePlatformBaseUrl } from "../../packages/api-client/src/index";

describe("acceptance endpoint configuration", () => {
  it("keeps the existing loopback default", () => {
    expect(resolvePlatformBaseUrl()).toBe(DEFAULT_PLATFORM_BASE_URL);
  });

  it("accepts an isolated HTTP endpoint and removes its trailing slash", () => {
    expect(resolvePlatformBaseUrl("http://127.0.0.1:49152/")).toBe("http://127.0.0.1:49152");
  });

  it("rejects invalid, credentialed, and non-HTTP endpoint overrides", () => {
    expect(resolvePlatformBaseUrl("not a url")).toBe(DEFAULT_PLATFORM_BASE_URL);
    expect(resolvePlatformBaseUrl("file:///tmp/platform")).toBe(DEFAULT_PLATFORM_BASE_URL);
    expect(resolvePlatformBaseUrl("http://user:secret@127.0.0.1:8090")).toBe(DEFAULT_PLATFORM_BASE_URL);
  });
});
