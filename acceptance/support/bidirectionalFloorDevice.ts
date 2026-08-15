import net, { type Server, type Socket } from "node:net";

const LED_FRAME_MAGIC = 0x67;
const FLOOR_INPUT_MAGIC = 0x68;
const FLOOR_INPUT_PAYLOAD_LENGTH = 5;
const MAX_FRAME_BYTES = 1024 * 1024;

type FloorEventMetadata = { action: "DOWN" | "UP"; x: number; y: number; sentAt: string };
type LedFrameMetadata = { payloadLength: number; receivedAt: string };

export class BidirectionalFloorDevice {
  readonly #port: number;
  readonly #expectedPayloadLength: number;
  readonly #frames: LedFrameMetadata[] = [];
  readonly #events: FloorEventMetadata[] = [];
  #server: Server | null = null;
  #socket: Socket | null = null;
  #buffer = Buffer.alloc(0);
  #lastError: string | null = null;

  constructor(port: number, width = 16, height = 16) {
    if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("FLOOR_DEVICE_PORT_INVALID");
    if (!Number.isInteger(width) || width < 1 || !Number.isInteger(height) || height < 1) {
      throw new Error("FLOOR_DEVICE_SIZE_INVALID");
    }
    this.#port = port;
    this.#expectedPayloadLength = width * height * 3;
  }

  async start(): Promise<void> {
    if (this.#server) return;
    const server = net.createServer((socket) => this.#accept(socket));
    this.#server = server;
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(this.#port, "127.0.0.1", () => {
        server.off("error", reject);
        resolve();
      });
    });
  }

  async sendFloorTap(x: number, y: number, timeoutMs = 10_000): Promise<void> {
    await this.waitForValidFrame(timeoutMs);
    await this.#writeInput("DOWN", x, y);
    await this.#writeInput("UP", x, y);
  }

  async waitForValidFrame(timeoutMs = 10_000): Promise<void> {
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeoutMs) {
      if (this.#frames.length > 0 && this.#socket && !this.#socket.destroyed) return;
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    const detail = this.#lastError ? `: ${this.#lastError}` : "";
    throw new Error(`FLOOR_VALID_LED_FRAME_TIMEOUT${detail}`);
  }

  diagnostics(): {
    connected: boolean;
    expectedPayloadLength: number;
    validFrameCount: number;
    frames: LedFrameMetadata[];
    events: FloorEventMetadata[];
    lastError: string | null;
  } {
    return {
      connected: Boolean(this.#socket && !this.#socket.destroyed),
      expectedPayloadLength: this.#expectedPayloadLength,
      validFrameCount: this.#frames.length,
      frames: [...this.#frames],
      events: [...this.#events],
      lastError: this.#lastError,
    };
  }

  async stop(): Promise<void> {
    const socket = this.#socket;
    this.#socket = null;
    socket?.destroy();
    const server = this.#server;
    this.#server = null;
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  #accept(socket: Socket): void {
    this.#socket?.destroy();
    this.#socket = socket;
    this.#buffer = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      this.#buffer = Buffer.concat([this.#buffer, chunk]);
      this.#consumeFrames();
    });
    socket.on("error", (error) => {
      this.#lastError = `FLOOR_SOCKET_ERROR: ${error.message}`;
    });
    socket.on("close", () => {
      if (this.#socket === socket) this.#socket = null;
    });
  }

  #consumeFrames(): void {
    let offset = 0;
    while (this.#buffer.length - offset >= 5) {
      if (this.#buffer[offset] !== LED_FRAME_MAGIC) {
        offset += 1;
        continue;
      }
      const payloadLength = this.#buffer.readUInt32BE(offset + 1);
      if (payloadLength > MAX_FRAME_BYTES) {
        this.#lastError = `FLOOR_LED_FRAME_TOO_LARGE: ${payloadLength}`;
        offset += 1;
        continue;
      }
      const frameEnd = offset + 5 + payloadLength;
      if (this.#buffer.length < frameEnd) break;
      if (payloadLength === this.#expectedPayloadLength) {
        this.#frames.push({ payloadLength, receivedAt: new Date().toISOString() });
        this.#trim(this.#frames);
        this.#lastError = null;
      } else {
        this.#lastError = `FLOOR_LED_FRAME_SIZE_MISMATCH: expected ${this.#expectedPayloadLength}, got ${payloadLength}`;
      }
      offset = frameEnd;
    }
    if (offset > 0) this.#buffer = this.#buffer.subarray(offset);
  }

  async #writeInput(action: "DOWN" | "UP", x: number, y: number): Promise<void> {
    if (!Number.isInteger(x) || x < 0 || x > 65_535 || !Number.isInteger(y) || y < 0 || y > 65_535) {
      throw new Error("FLOOR_INPUT_COORDINATES_INVALID");
    }
    const socket = this.#socket;
    if (!socket || socket.destroyed) throw new Error("FLOOR_INPUT_SOCKET_UNAVAILABLE");
    const frame = Buffer.from([
      FLOOR_INPUT_MAGIC,
      0x00, 0x00, 0x00, FLOOR_INPUT_PAYLOAD_LENGTH,
      action === "DOWN" ? 0x01 : 0x00,
      (x >>> 8) & 0xff, x & 0xff,
      (y >>> 8) & 0xff, y & 0xff,
    ]);
    await new Promise<void>((resolve, reject) => {
      socket.write(frame, (error) => error ? reject(error) : resolve());
    });
    this.#events.push({ action, x, y, sentAt: new Date().toISOString() });
    this.#trim(this.#events);
  }

  #trim(items: unknown[]): void {
    if (items.length > 50) items.splice(0, items.length - 50);
  }
}
