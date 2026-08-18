export type KioskScreen = "home" | "phone" | "confirm" | "register" | "swipe" | "success" | "info-phone" | "info-result";
export type KioskOverlay = "none" | "avatar-source" | "avatar-library";
export type KeyboardLayout = "numeric" | "alphabetic";
export type InputTarget = "phone" | "infoPhone" | "name" | "birthYear" | "birthMonth" | "birthDay" | "staffExitPassword";
export type Gender = "male" | "female" | "secret" | "";

export interface KioskSession {
  phone: string;
  infoPhone: string;
  name: string;
  birthYear: string;
  birthMonth: string;
  birthDay: string;
  gender: Gender;
  avatarId: string;
  memberId: number | null;
  wristbandUid: string;
  durationMinutes: number | null;
  wristbandStatus: "idle" | "waiting" | "detected";
}

export interface DemoMember {
  id?: number;
  phone: string;
  name: string;
  avatarId: string;
}

export interface AvatarSpec {
  id: string;
  label: string;
  skin: string;
  hair: string;
  shirt: string;
  accent: string;
  hairStyle: "short" | "wave" | "curl" | "bun" | "bob" | "crop";
  accessory?: "glasses" | "headband" | "cap" | "freckles";
}
