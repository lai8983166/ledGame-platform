import { createPlatformApiClient, resolvePlatformBaseUrl } from "@ledgame/platform-api-client";
import { operatorSession } from "./operatorSession";

export const platformBaseUrl = resolvePlatformBaseUrl(import.meta.env.VITE_PLATFORM_BASE_URL);
export const platformApiBase = `${platformBaseUrl}/api`;
export const platformApi = createPlatformApiClient({
  baseUrl: platformBaseUrl,
  transport: window.memberAdminDesktop?.request,
  operatorIdProvider: () => operatorSession.current.value?.id,
});
