import net from "node:net";
import { describe, expect, it } from "vitest";
import { allocateLoopbackPort } from "./runtime";
import { BidirectionalFloorDevice } from "./bidirectionalFloorDevice";

function connect(port: number): Promise<net.Socket> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port }, () => resolve(socket));
    socket.once("error", reject);
  });
}

function ledFrame(payloadLength: number): Buffer {
  const frame = Buffer.alloc(5 + payloadLength);
  frame[0] = 0x67;
  frame.writeUInt32BE(payloadLength, 1);
  return frame;
}

describe("BidirectionalFloorDevice", () => {
  it("waits for a valid LED frame before returning ordered DOWN and UP", async () => {
    const port = await allocateLoopbackPort();
    const floor = new BidirectionalFloorDevice(port, 2, 1);
    await floor.start();
    const client = await connect(port);
    const received: Buffer[] = [];
    client.on("data", (chunk) => received.push(chunk));
    try {
      const tap = floor.sendFloorTap(1, 0);
      await new Promise((resolve) => setTimeout(resolve, 30));
      expect(received).toHaveLength(0);
      client.write(ledFrame(6));
      await tap;
      await new Promise((resolve) => setTimeout(resolve, 30));

      const input = Buffer.concat(received);
      expect(input).toHaveLength(20);
      expect([input[0], input[5], input[6], input[7], input[8], input[9]]).toEqual([0x68, 1, 0, 1, 0, 0]);
      expect([input[10], input[15], input[16], input[17], input[18], input[19]]).toEqual([0x68, 0, 0, 1, 0, 0]);
      expect(floor.diagnostics()).toMatchObject({ validFrameCount: 1, lastError: null });
    } finally {
      client.destroy();
      await floor.stop();
    }
  });

  it("reports a bounded size mismatch instead of accepting an invalid LED frame", async () => {
    const port = await allocateLoopbackPort();
    const floor = new BidirectionalFloorDevice(port, 2, 1);
    await floor.start();
    const client = await connect(port);
    try {
      client.write(ledFrame(3));
      await expect(floor.waitForValidFrame(80)).rejects.toThrow(/SIZE_MISMATCH/);
      expect(floor.diagnostics()).toMatchObject({ validFrameCount: 0 });
    } finally {
      client.destroy();
      await floor.stop();
    }
  });
});
