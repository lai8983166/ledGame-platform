const net = require("node:net");
const os = require("node:os");

const PORT_MIN = 1024;
const PORT_MAX = 65535;

function validatePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < PORT_MIN || port > PORT_MAX) {
    throw Object.assign(new Error(`端口必须是 ${PORT_MIN}-${PORT_MAX} 之间的整数`), { code: "INVALID_PORT" });
  }
  return port;
}

function validateHost(value) {
  const host = String(value || "").trim();
  if (!host || host.length > 253 || /[/:\\?#@\s]/.test(host)) {
    throw Object.assign(new Error("管理端地址格式不正确"), { code: "INVALID_HOST" });
  }
  const validIpv4 = /^(?:\d{1,3}\.){3}\d{1,3}$/.test(host) && host.split(".").every((part) => Number(part) <= 255);
  const validHostname = /^(?=.{1,253}$)(?:[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?\.)*[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(host);
  if (!validIpv4 && !validHostname) {
    throw Object.assign(new Error("管理端地址格式不正确"), { code: "INVALID_HOST" });
  }
  return host;
}

function buildHttpBaseUrl(host, port) {
  return `http://${validateHost(host)}:${validatePort(port)}`;
}

function classifyConnectionError(error) {
  if (error?.name === "AbortError" || error?.code === "HEALTH_TIMEOUT") return "TIMEOUT";
  if (["ECONNREFUSED", "ECONNRESET"].includes(error?.cause?.code || error?.code)) return "REFUSED";
  return "UNREACHABLE";
}

async function checkHealth(baseUrl, { timeoutMs = 3000, fetchImpl = fetch } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(`${baseUrl}/api/health`, { signal: controller.signal });
    if (!response.ok) throw Object.assign(new Error(`健康检查返回 ${response.status}`), { code: "BAD_STATUS" });
    return true;
  } catch (error) {
    const classified = Object.assign(new Error("无法连接会员管理端"), {
      code: classifyConnectionError(error),
      cause: error,
    });
    throw classified;
  } finally {
    clearTimeout(timer);
  }
}

function listLanIpv4() {
  return Object.values(os.networkInterfaces()).flatMap((entries) => entries || [])
    .filter((entry) => entry.family === "IPv4" && !entry.internal)
    .map((entry) => entry.address);
}

async function assertPortAvailable(port, host = "0.0.0.0") {
  const validated = validatePort(port);
  await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", (error) => reject(Object.assign(new Error(`端口 ${validated} 已被占用`), { code: "PORT_IN_USE", cause: error })));
    server.listen(validated, host, () => server.close(resolve));
  });
  return validated;
}

module.exports = {
  PORT_MIN, PORT_MAX, validatePort, validateHost, buildHttpBaseUrl,
  classifyConnectionError, checkHealth, listLanIpv4, assertPortAvailable,
};
