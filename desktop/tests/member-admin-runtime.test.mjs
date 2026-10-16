import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { createProductConfigStore } from "../shared/config-store.cjs";
import { resolveMemberAdminResources } from "../member-admin/runtime.cjs";

describe("member admin desktop runtime contract", () => {
  it("only exposes local IP addresses in the runtime card", () => {
    const source = readFileSync(new URL("../../apps/member-admin/src/components/DesktopRuntimeCard.vue", import.meta.url), "utf8");
    const template = source.split("<template>")[1].split("</template>")[0];
    expect(template).toContain("runtimeLocalIp");
    expect(template).not.toMatch(/<input|<button|runtimeTitle|diagnostics\.(message|dataPath|logPath|lastError|port)/);
    expect(source).toContain(".hostname");
  });
  it("keeps configuration and mutable data under the product userData directory", async () => {
    const store = createProductConfigStore("C:/test/member-admin", "member-admin", { port: 8090 });
    expect(store.configPath).toBe("C:\\test\\member-admin\\member-admin.json");
    expect(store.dataPath("platform.db")).toBe("C:\\test\\member-admin\\data\\platform.db");
    expect(store.logPath("server.log")).toBe("C:\\test\\member-admin\\logs\\server.log");
  });

  it("resolves packaged JAR and JRE independently of the current working directory", () => {
    const resources = resolveMemberAdminResources({
      packaged: true,
      resourcesPath: "C:/Program Files/LED Game/resources",
      projectRoot: "D:/not-used",
    });
    expect(resources.javaExecutable).toContain("jre\\bin\\java.exe");
    expect(resources.serverJar).toContain("backend\\ledgame-platform-server.jar");
  });
});
