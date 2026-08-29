import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync(new URL("./views/RoomsView.vue", import.meta.url), "utf8");

describe("RoomsView game time UI", () => {
  it("uses a local display clock without increasing room API polling", () => {
    expect(source).toContain("roomGameTimeText");
    expect(source).toContain("clockTimer");
    expect(source).toMatch(/clockTimer\s*=\s*window\.setInterval\(\(\)\s*=>\s*\{?\s*clockNow\.value\s*=\s*Date\.now\(\)/);
    expect(source.match(/platformApi\.listRooms\(\)/g)).toHaveLength(1);
  });

  it("keeps the open drawer derived from the latest room collection", () => {
    expect(source).toContain("selectedRoomId");
    expect(source).toMatch(/selectedRoom\s*=\s*computed\(\(\)\s*=>\s*rooms\.value\.find/);
    expect(source).toContain("roomTimeText(room)");
    expect(source).toContain("roomTimeText(selectedRoom)");
  });
});
