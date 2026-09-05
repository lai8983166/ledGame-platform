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
    backupState?: string;
    phase?: string;
    error?: string;
    concurrencyTestRunId?: string | null;
    concurrencyTestMode?: boolean;
  }

  interface Window {
    memberAdminDesktop?: {
      request(request: PlatformApiTransportRequest): Promise<PlatformApiTransportResponse>;
      diagnostics(): Promise<MemberAdminDiagnostics>;
      restartBackend(port: number): Promise<MemberAdminDiagnostics>;
      retryBackend(): Promise<MemberAdminDiagnostics>;
      chooseBackupDatabase(operatorId: number): Promise<import("@ledgame/platform-api-client").DatabaseBackupCandidate | null>;
      importBackupDatabase(candidateId: string, operatorId: number): Promise<{ imported: boolean; revision: number; requiresLogin: boolean }>;
      onStatus(listener: (status: Partial<MemberAdminDiagnostics>) => void): () => void;
    };
  }
}
