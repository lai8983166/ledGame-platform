export type WristbandScanState = "idle" | "waiting" | "submitting";

export interface WristbandScanSession {
  state: WristbandScanState;
  buffer: string;
  revision: number;
}

export interface WristbandScanFrame {
  uid: string;
  revision: number;
}

export function createWristbandScanSession(): WristbandScanSession {
  return { state: "idle", buffer: "", revision: 0 };
}

export function startWristbandScan(session: WristbandScanSession): void {
  session.revision += 1;
  session.state = "waiting";
  session.buffer = "";
}

export function cancelWristbandScan(session: WristbandScanSession): void {
  session.revision += 1;
  session.state = "idle";
  session.buffer = "";
}

export function consumeWristbandScanKey(
  session: WristbandScanSession,
  key: string,
): WristbandScanFrame | null {
  if (session.state !== "waiting") return null;
  if (/^\d$/.test(key)) {
    if (session.buffer.length < 32) session.buffer += key;
    return null;
  }
  if (key !== "Enter" || !session.buffer) return null;
  const frame = { uid: session.buffer, revision: session.revision };
  session.buffer = "";
  session.state = "submitting";
  return frame;
}

export function isWristbandScanCurrent(session: WristbandScanSession, revision: number): boolean {
  return session.revision === revision && session.state === "submitting";
}

export function completeWristbandScan(session: WristbandScanSession, revision: number): boolean {
  if (!isWristbandScanCurrent(session, revision)) return false;
  session.state = "idle";
  session.buffer = "";
  return true;
}

export function failWristbandScan(session: WristbandScanSession, revision: number): boolean {
  if (!isWristbandScanCurrent(session, revision)) return false;
  session.state = "waiting";
  session.buffer = "";
  return true;
}
