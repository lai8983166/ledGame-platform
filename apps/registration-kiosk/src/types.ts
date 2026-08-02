export type KioskScreen = "home" | "phone" | "register" | "swipe" | "success";
export type KioskOverlay = "none" | "avatar-source" | "avatar-library";
export type KeyboardLayout = "numeric" | "alphabetic";
export type InputTarget = "phone" | "name" | "birthYear" | "birthMonth" | "birthDay";
export type Gender = "male" | "female" | "secret" | "";

export interface KioskSession {
  phone: string;
  name: string;
  birthYear: string;
  birthMonth: string;
  birthDay: string;
  gender: Gender;
  avatarId: string;
  wristbandStatus: "idle" | "waiting" | "detected";
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
