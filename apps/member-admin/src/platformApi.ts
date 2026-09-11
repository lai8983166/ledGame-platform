import { createPlatformApiClient, resolvePlatformBaseUrl } from "@ledgame/platform-api-client";
import { operatorSession } from "./operatorSession";

export const platformBaseUrl = resolvePlatformBaseUrl(import.meta.env.VITE_PLATFORM_BASE_URL);
export const platformApiBase = `${platformBaseUrl}/api`;
export const platformApi = createPlatformApiClient({
  baseUrl: platformBaseUrl,
  transport: window.memberAdminDesktop?.request
    ? async (request) => {
        const response = await window.memberAdminDesktop!.request(request);
        if (response.status === 403) {
          try {
            const body = JSON.parse(response.body) as { code?: string };
            if (body.code === "OPERATOR_SESSION_INVALID") operatorSession.logout();
          } catch { /* Keep the original backend response. */ }
        }
        return response;
      }
    : undefined,
  operatorIdProvider: () => operatorSession.current.value?.id,
});
