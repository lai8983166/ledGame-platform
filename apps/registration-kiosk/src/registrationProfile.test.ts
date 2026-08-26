import { describe, expect, it } from "vitest";
import { avatars } from "./avatars";
import {
  DEFAULT_AVATAR_IDS,
  avatarOptionsForGender,
  createDefaultAvatarId,
  reconcileAvatarForGender,
} from "./registrationProfile";

describe("registration profile avatars", () => {
  it("starts every registration with a persisted neutral default avatar", () => {
    const avatarId = createDefaultAvatarId();
    expect(avatarId).toBe(DEFAULT_AVATAR_IDS.neutral);
    expect(avatars.some((avatar) => avatar.id === avatarId)).toBe(true);
  });

  it("offers only male or female avatars for an explicit gender", () => {
    const male = avatarOptionsForGender("male");
    const female = avatarOptionsForGender("female");
    expect(male.length).toBeGreaterThan(0);
    expect(female.length).toBeGreaterThan(0);
    expect(male.every((avatar) => avatar.audience === "male")).toBe(true);
    expect(female.every((avatar) => avatar.audience === "female")).toBe(true);
    expect(avatarOptionsForGender("secret")).toHaveLength(avatars.length);
  });

  it("keeps compatible choices and replaces incompatible choices with the gender default", () => {
    const maleChoice = avatarOptionsForGender("male")[1].id;
    expect(reconcileAvatarForGender(maleChoice, "male")).toBe(maleChoice);
    expect(reconcileAvatarForGender(maleChoice, "female")).toBe(DEFAULT_AVATAR_IDS.female);
    expect(reconcileAvatarForGender("missing-avatar", "secret")).toBe(DEFAULT_AVATAR_IDS.neutral);
  });
});
