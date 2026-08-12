import { createPlatformApiClient, resolvePlatformBaseUrl } from "@ledgame/platform-api-client";

export const platformBaseUrl = resolvePlatformBaseUrl(import.meta.env.VITE_PLATFORM_BASE_URL);
export const platformApiBase = `${platformBaseUrl}/api`;
export const platformApi = createPlatformApiClient({ baseUrl: platformBaseUrl });
