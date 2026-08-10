import type { WristbandState } from "./types";

export const normalizeWristbandUid = (value: string) => value.replace(/\D/g, "");

export const canClearWristbandBalance = (state: WristbandState) => state === "charged";

export const canReclaimWristband = (state: WristbandState) => state === "expired";
