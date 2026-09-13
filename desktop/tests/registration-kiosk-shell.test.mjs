import { describe, expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import { capabilitiesForWindow, createKioskLifecycle } from "../registration-kiosk/runtime.cjs";

describe("registration kiosk desktop shell contract", () => {
  it("starts with only an operator window and creates one kiosk after a successful test", () => {
    const lifecycle = createKioskLifecycle();
    expect(lifecycle.snapshot()).toEqual({ operatorVisible: true, kioskOpen: false, connectionTested: false });
    expect(() => lifecycle.startKiosk()).toThrow(/test/i);
    lifecycle.connectionSucceeded();
    lifecycle.startKiosk();
    lifecycle.startKiosk();
    expect(lifecycle.snapshot()).toEqual({ operatorVisible: false, kioskOpen: true, connectionTested: true });
  });

  it("does not expose operator configuration capabilities to the customer window", () => {
    expect(capabilitiesForWindow("operator")).toContain("save-settings");
    expect(capabilitiesForWindow("kiosk")).not.toContain("save-settings");
    expect(capabilitiesForWindow("kiosk")).not.toContain("start-kiosk");
  });

  it("opts the renderer into media permission without exposing filesystem access", async () => {
    const source = await readFile(new URL("../registration-kiosk/main.cjs", import.meta.url), "utf8");
    expect(source).toContain("setPermissionRequestHandler");
    expect(source).toContain("permission === \"media\"");
    expect(source).toContain("setPermissionCheckHandler");
    expect(source).toContain("nodeIntegration: false");
    expect(source).toContain("sandbox: true");
  });
});
