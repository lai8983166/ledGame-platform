import type { PlatformApiTransportRequest, PlatformApiTransportResponse } from "@ledgame/platform-api-client";

export {};

declare global {
  interface Window {
    registrationDesktop?: {
      windowKind: "operator" | "kiosk";
      request(request: PlatformApiTransportRequest): Promise<PlatformApiTransportResponse>;
      readSettings?(): Promise<{ host: string; port: number; connectionState: ConnectionState }>;
      saveSettings?(settings: { host: string; port: number }): Promise<{ host: string; port: number }>;
      testConnection?(settings: { host: string; port: number }): Promise<{ ok: boolean; code?: string; message?: string }>;
      startKiosk?(): Promise<unknown>;
      staffExit?(): Promise<void>;
      onConnectionState(listener: (state: ConnectionState) => void): () => void;
    };
  }

  interface ConnectionState {
    online: boolean;
    code: string;
    message: string;
  }
}
