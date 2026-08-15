import type { PlatformApiTransportRequest, PlatformApiTransportResponse } from "@ledgame/platform-api-client";

export {};

declare global {
  interface MemberAdminDiagnostics {
    state: "starting" | "online" | "failed" | "stopping";
    message: string;
    lastError?: string | null;
    port: number;
    dataPath: string;
    logPath: string;
    lanUrls: string[];
    recentLogs: string[];
    error?: string;
  }

  interface Window {
    memberAdminDesktop?: {
      request(request: PlatformApiTransportRequest): Promise<PlatformApiTransportResponse>;
      diagnostics(): Promise<MemberAdminDiagnostics>;
      restartBackend(port: number): Promise<MemberAdminDiagnostics>;
      retryBackend(): Promise<MemberAdminDiagnostics>;
      onStatus(listener: (status: Partial<MemberAdminDiagnostics>) => void): () => void;
    };
  }
}
