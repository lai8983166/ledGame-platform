export interface PlatformApiClientOptions {
  baseUrl?: string;
}

export interface PlatformApiClient {
  request<TResponse = unknown>(
    path: string,
    options?: RequestInit,
  ): Promise<TResponse | null>;
}

interface ErrorResponse {
  message?: string;
}

export function createPlatformApiClient({
  baseUrl = "http://127.0.0.1:8090",
}: PlatformApiClientOptions = {}): PlatformApiClient {
  const normalizedBaseUrl = baseUrl.replace(/\/$/, "");

  return {
    async request<TResponse = unknown>(
      path: string,
      options: RequestInit = {},
    ): Promise<TResponse | null> {
      const headers = new Headers(options.headers);
      if (!headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }

      const response = await fetch(`${normalizedBaseUrl}${path}`, {
        ...options,
        headers,
      });
      const text = await response.text();
      const data = text ? (JSON.parse(text) as unknown) : null;

      if (!response.ok) {
        const message =
          typeof data === "object" &&
          data !== null &&
          "message" in data &&
          typeof (data as ErrorResponse).message === "string"
            ? (data as ErrorResponse).message
            : `Platform request failed: ${response.status}`;
        throw new Error(message);
      }

      return data as TResponse | null;
    },
  };
}
