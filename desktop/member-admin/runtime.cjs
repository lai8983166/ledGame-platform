const fs = require("node:fs");
const path = require("node:path");

function resolveMemberAdminResources({ packaged, resourcesPath, projectRoot, javaHome = process.env.JAVA_HOME }) {
  if (packaged) {
    return {
      javaExecutable: path.win32.join(resourcesPath, "jre", "bin", "java.exe"),
      serverJar: path.win32.join(resourcesPath, "backend", "ledgame-platform-server.jar"),
    };
  }
  const target = path.join(projectRoot, "server", "target");
  const jar = fs.existsSync(target)
    ? fs.readdirSync(target).find((name) => /^ledgame-platform-server-.*\.jar$/.test(name) && !name.endsWith(".original"))
    : undefined;
  return {
    javaExecutable: javaHome ? path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : "java",
    serverJar: jar ? path.join(target, jar) : path.join(target, "ledgame-platform-server.jar"),
  };
}

module.exports = { resolveMemberAdminResources };
