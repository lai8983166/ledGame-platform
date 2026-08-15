const { buildHttpBaseUrl } = require("./network.cjs");

const ALLOWED_METHODS = new Set(["GET", "POST", "PUT", "DELETE"]);
const MAX_REQUEST_BYTES = 256 * 1024;
const MAX_RESPONSE_BYTES = 1024 * 1024;

function validateApiRequest(request) {
  const path = String(request?.path || "");
  const method = String(request?.method || "GET").toUpperCase();
  if (!path.startsWith("/api/") || path.includes("..") || path.includes("://")) {
    throw Object.assign(new Error("只允许访问平台 /api 路径"), { code: "INVALID_API_PATH" });
  }
  if (!ALLOWED_METHODS.has(method)) {
    throw Object.assign(new Error("不允许的 HTTP 方法"), { code: "INVALID_API_METHOD" });
  }
  const body = request?.body;
  if (body !== undefined && typeof body !== "string") {
    throw Object.assign(new Error("请求正文必须是 JSON 字符串"), { code: "INVALID_API_BODY" });
  }
  if (body && Buffer.byteLength(body, "utf8") > MAX_REQUEST_BYTES) {
    throw Object.assign(new Error("请求正文过大"), { code: "REQUEST_TOO_LARGE" });
  }
  if (body) JSON.parse(body);
  return { path, method, body, headers: { "content-type": "application/json" } };
}

function createApiTransport(getTarget, { fetchImpl = fetch, timeoutMs = 10000 } = {}) {
  return async (rawRequest) => {
    const request = validateApiRequest(rawRequest);
    const target = await getTarget();
    const baseUrl = typeof target === "string" ? target : buildHttpBaseUrl(target.host, target.port);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(`${baseUrl}${request.path}`, {
        method: request.method,
        headers: request.headers,
        body: request.body,
        signal: controller.signal,
      });
      const body = await response.text();
      if (Buffer.byteLength(body, "utf8") > MAX_RESPONSE_BYTES) {
        throw Object.assign(new Error("平台响应过大"), { code: "RESPONSE_TOO_LARGE" });
      }
      return { status: response.status, body };
    } finally {
      clearTimeout(timer);
    }
  };
}

module.exports = { ALLOWED_METHODS, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES, validateApiRequest, createApiTransport };
