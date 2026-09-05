import type { StepResult } from "./types.js";

export class PlatformClient {
  constructor(
    private readonly baseUrl: string,
    private readonly timeoutMs: number,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async step(name: string, method: string, path: string, body?: unknown): Promise<StepResult> {
    const started = new Date();
    const startedAt = started.toISOString();
    try {
      const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      const raw = (await response.text()).slice(0, 64 * 1024);
      let parsed: unknown = raw;
      try { parsed = raw ? JSON.parse(raw) : null; } catch { /* Keep bounded text. */ }
      const ended = new Date();
      return {
        name, method, path, kind: "http", startedAt, endedAt: ended.toISOString(),
        durationMs: ended.getTime() - started.getTime(), status: response.status, response: parsed,
      };
    } catch (error) {
      const ended = new Date();
      const nameValue = error instanceof Error ? error.name : "Error";
      const timeout = nameValue === "TimeoutError" || nameValue === "AbortError";
      return {
        name, method, path, kind: timeout ? "timeout" : "network",
        startedAt, endedAt: ended.toISOString(), durationMs: ended.getTime() - started.getTime(),
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }
}

export function stepSucceeded(step: StepResult): boolean {
  return step.kind === "http" && Number(step.status) >= 200 && Number(step.status) < 300;
}
