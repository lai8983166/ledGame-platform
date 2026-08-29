import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { RoomStatus } from "../../packages/api-client/src";
import { mapRoomStatus, roomGameTimeText } from "../../apps/member-admin/src/roomStatus";

type ContractScenario = {
  name: string;
  eventType: string;
  eventAt: string;
  displayAt: string;
  state: RoomStatus["state"];
  expectedText: string | null;
};

const fixture = JSON.parse(readFileSync(
  new URL("../fixtures/game-time-contract.json", import.meta.url),
  "utf8",
)) as { scenarios: ContractScenario[] };

describe("跨端全局游戏时间契约", () => {
  for (const [index, scenario] of fixture.scenarios.entries()) {
    it(scenario.name, () => {
      const roomStatus: RoomStatus = {
        ip: "192.168.1.25",
        deviceId: "game-01",
        roomId: "room-01",
        roomName: "验收房间",
        connectionId: "connection-1",
        online: true,
        state: scenario.state,
        lastSequence: index + 1,
        lastEventType: scenario.eventType,
        lastEventAt: scenario.eventAt,
        queueLength: 0,
      };

      const room = mapRoomStatus(roomStatus);
      expect(roomGameTimeText(room, Date.parse(scenario.displayAt))).toBe(scenario.expectedText);
      if (scenario.state.gameTime) {
        expect(room.gameTimeMode).toBe(scenario.state.gameTime.mode);
        expect(room.gameTimeRemainingMillis).toBe(scenario.state.gameTime.remainingMillis);
        expect(room.gameTimeRunning).toBe(scenario.state.gameTime.running);
      }
    });
  }
});
