import net from 'node:net';
import { performance } from 'node:perf_hooks';

// TCP output substitute only: these counters are never labeled real SDK/UDP evidence.
export class FloorDevice {
  #server; #socket; #buffer = Buffer.alloc(0);
  validFrames = 0; rejectedFrames = 0; receivedBytes = 0; lastError = null;
  runningFrames = 0; transitionFrames = 0; unclassifiedFrames = 0;
  #phase = 'UNKNOWN'; #previousBytes; #transitionUntil = 0;
  constructor(width, height, now = () => performance.now()) { this.payloadBytes = width * height * 3; this.now = now; }
  observeState(state) {
    const bytes = state.width * state.height * 3;
    if (!Number.isSafeInteger(bytes) || bytes < 3) throw new Error('地砖采集缺少有效运行尺寸');
    if (this.#phase !== state.engineState || bytes !== this.payloadBytes) {
      this.#previousBytes = this.payloadBytes; this.payloadBytes = bytes;
      this.#phase = state.engineState; this.#transitionUntil = this.now() + 1000;
    }
  }
  async start(port) {
    this.#server = net.createServer((socket) => {
      this.#socket?.destroy(); this.#socket = socket; this.#buffer = Buffer.alloc(0);
      socket.on('data', (chunk) => this.consume(chunk));
      socket.on('error', (error) => { this.lastError = error.message.slice(0, 512); });
    });
    await new Promise((resolve, reject) => {
      this.#server.once('error', reject);
      this.#server.listen(port, '127.0.0.1', resolve);
    });
  }
  consume(chunk) {
    this.receivedBytes += chunk.length;
    this.#buffer = Buffer.concat([this.#buffer, chunk]);
    while (this.#buffer.length >= 5) {
      if (this.#buffer[0] !== 0x67) { this.rejectedFrames++; this.lastError = '无效帧头'; this.#buffer = this.#buffer.subarray(1); continue; }
      const length = this.#buffer.readUInt32BE(1);
      if (length > 1024 * 1024) {
        this.rejectedFrames++; this.lastError = '帧长度超过上限'; this.#buffer = Buffer.alloc(0); return;
      }
      if (this.#buffer.length < 5 + length) return;
      if (this.#phase === 'UNKNOWN') this.unclassifiedFrames++;
      if (length === this.payloadBytes) {
        this.validFrames++;
        if (this.#phase === 'RUNNING') this.runningFrames++;
      }
      else if (this.#phase === 'UNKNOWN'
        || (this.#phase === 'STOPPED' && length === 16 * 16 * 3)
        || (this.now() < this.#transitionUntil && length === this.#previousBytes)) this.transitionFrames++;
      else { this.rejectedFrames++; this.lastError = `尺寸不匹配：阶段 ${this.#phase}，期望 ${this.payloadBytes}，收到 ${length}`; }
      this.#buffer = this.#buffer.subarray(5 + length);
    }
    // Do not retain the large original ArrayBuffer behind an empty slice.
    if (!this.#buffer.length) this.#buffer = Buffer.alloc(0);
  }
  snapshot() { return { source: 'simulated-tcp', validFrames: this.validFrames, rejectedFrames: this.rejectedFrames,
    receivedBytes: this.receivedBytes, runningFrames: this.runningFrames, transitionFrames: this.transitionFrames,
    unclassifiedFrames: this.unclassifiedFrames, bufferedBytes: this.#buffer.length, lastError: this.lastError }; }
  async close() {
    this.#socket?.destroy();
    if (this.#server) await new Promise((resolve) => this.#server.close(resolve));
  }
}
