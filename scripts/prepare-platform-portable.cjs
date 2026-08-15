const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const root = path.resolve(__dirname, "..");
const outputRoot = path.join(root, "build-resources", "platform");
if (!outputRoot.startsWith(path.join(root, "build-resources"))) throw new Error("invalid portable output path");
const backendOutput = path.join(outputRoot, "backend");
const jreOutput = path.join(outputRoot, "jre");

fs.rmSync(outputRoot, { recursive: true, force: true });
fs.mkdirSync(backendOutput, { recursive: true });
const maven = process.platform === "win32" ? "mvn.cmd" : "mvn";
execFileSync(maven, ["-q", "-f", path.join(root, "server", "pom.xml"), "package", "-DskipTests"], {
  stdio: "inherit",
  shell: process.platform === "win32",
});

const target = path.join(root, "server", "target");
const jarName = fs.readdirSync(target).find((name) => /^ledgame-platform-server-.*\.jar$/.test(name) && !name.endsWith(".original"));
if (!jarName) throw new Error("未找到 Spring Boot JAR");
fs.copyFileSync(path.join(target, jarName), path.join(backendOutput, "ledgame-platform-server.jar"));

const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error("打包会员管理端前请设置 JAVA_HOME（JDK 17 或更高版本）");
const jlink = path.join(javaHome, "bin", process.platform === "win32" ? "jlink.exe" : "jlink");
if (!fs.existsSync(jlink)) throw new Error(`JAVA_HOME 中未找到 jlink: ${jlink}`);
execFileSync(jlink, [
  "--add-modules", "java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported",
  "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2", "--output", jreOutput,
], { stdio: "inherit" });

const javaExecutable = path.join(jreOutput, "bin", process.platform === "win32" ? "java.exe" : "java");
execFileSync(javaExecutable, ["-version"], { stdio: "inherit" });
process.stdout.write(`便携资源已准备：${outputRoot}\n`);
