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
    diskFreePercent?: number | null;
    concurrencyTestRunId?: string | null;
    concurrencyTestMode?: boolean;
  }

  interface Window {
    memberAdminDesktop?: {
      request(request: PlatformApiTransportRequest): Promise<PlatformApiTransportResponse>;
      diagnostics(): Promise<MemberAdminDiagnostics>;
      applyBranding(input: { title?: string; iconDataUrl?: string | null; secondaryDisplayEnabled?: boolean }): Promise<{ applied: boolean }>;
      restartBackend(port: number): Promise<MemberAdminDiagnostics>;
      retryBackend(): Promise<MemberAdminDiagnostics>;
      chooseBackupDatabase(operatorId: number): Promise<import("@ledgame/platform-api-client").DatabaseBackupCandidate | null>;
      importBackupDatabase(candidateId: string, operatorId: number): Promise<{ imported: boolean; revision: number; requiresLogin: boolean }>;
      createDatabaseRecoveryRequest(operatorId: number): Promise<{ request: import("@ledgame/platform-api-client").DatabaseRecoveryRequest; path: string } | null>;
      cancelDatabaseRecoveryRequest(requestId: string, operatorId: number): Promise<void>;
      selectDatabaseRecoveryResponse(operatorId: number): Promise<{ backupPath: string; responsePath: string; requestId: string; revision?: number; instanceId?: string } | null>;
      importDatabaseRecoveryResponse(operatorId: number, backupPath?: string, responsePath?: string): Promise<{ imported: boolean; revision: number; requiresLogin: boolean } | null>;
      exportData(dataset: "members" | "wristband-charges" | "game-plays", operatorId: number): Promise<{ canceled: boolean; filePath?: string }>;
      onStatus(listener: (status: Partial<MemberAdminDiagnostics>) => void): () => void;
    };
  }
}
