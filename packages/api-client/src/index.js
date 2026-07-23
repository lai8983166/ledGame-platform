export function createPlatformApiClient({ baseUrl = "http://127.0.0.1:8090" } = {}) {
  const normalizedBaseUrl = baseUrl.replace(/\/$/, "");

  return {
    async request(path, options = {}) {
      const response = await fetch(`${normalizedBaseUrl}${path}`, {
        headers: {
          "Content-Type": "application/json",
          ...(options.headers || {}),
        },
        ...options,
      });
      const text = await response.text();
      const data = text ? JSON.parse(text) : null;
      if (!response.ok) {
        throw new Error(data?.message || `Platform request failed: ${response.status}`);
      }
      return data;
    },
  };
}
