import { avatars } from "./avatars";
import type { AvatarSpec, Gender } from "./types";

export const DEFAULT_AVATAR_IDS = {
  male: "leo",
  female: "nova",
  neutral: "ash",
} as const;

export function createDefaultAvatarId(): string {
  return DEFAULT_AVATAR_IDS.neutral;
}
export function avatarOptionsForGender(gender: Gender): AvatarSpec[] {
  if (gender === "male" || gender === "female") {
    return avatars.filter((avatar) => avatar.audience === gender);
  }
  return avatars;
}

export function reconcileAvatarForGender(currentAvatarId: string, gender: Gender): string {
  const current = avatars.find((avatar) => avatar.id === currentAvatarId);
  if (gender === "male" || gender === "female") {
    return current?.audience === gender ? current.id : DEFAULT_AVATAR_IDS[gender];
  }
  return current?.id ?? DEFAULT_AVATAR_IDS.neutral;
}
