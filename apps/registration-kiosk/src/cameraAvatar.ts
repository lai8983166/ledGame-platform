export const MAX_CAPTURE_SIDE = 1024;
export const OUTPUT_CAPTURE_SIDE = 512;
export const MAX_CAPTURE_BYTES = 256 * 1024;

export type CameraErrorCode =
  | "UNSUPPORTED"
  | "PERMISSION_DENIED"
  | "NO_CAMERA"
  | "BUSY"
  | "DISCONNECTED"
  | "CAPTURE_FAILED";

export interface CapturedAvatar {
  dataUrl: string;
  base64: string;
  mimeType: "image/jpeg";
  width: number;
  height: number;
  bytes: number;
}

export interface CameraErrorContext {
  /** Number of video inputs visible after the open attempt, when known. */
  availableDeviceCount?: number;
}

export function cameraErrorCode(error: unknown, context: CameraErrorContext = {}): CameraErrorCode {
  const explicitCode = String((error as { code?: unknown })?.code ?? "").toUpperCase();
  const name = String((error as { name?: unknown })?.name ?? "").toLowerCase();
  const message = String((error as { message?: unknown })?.message ?? "");
  if (explicitCode === "NO_CAMERA") return "NO_CAMERA";
  if (explicitCode === "UNSUPPORTED") return "UNSUPPORTED";
  if (message === "CAPTURE_FAILED") return "CAPTURE_FAILED";
  if (name === "notallowederror" || name === "securityerror") return "PERMISSION_DENIED";
  if (name === "notfounderror" || name === "overconstrainederror") return "NO_CAMERA";
  // Chromium reports NotReadableError both for an occupied/broken device and
  // for machines without a camera. Enumerating after the failed open lets the
  // UI avoid claiming that a nonexistent camera is occupied.
  if (name === "notreadableerror") return context.availableDeviceCount === 0 ? "NO_CAMERA" : "BUSY";
  if (name === "aborterror") return "BUSY";
  if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) return "UNSUPPORTED";
  return "DISCONNECTED";
}

export function cropSquareBounds(width: number, height: number) {
  const side = Math.min(width, height);
  return {
    sourceX: Math.floor((width - side) / 2),
    sourceY: Math.floor((height - side) / 2),
    sourceSide: side,
    outputSide: Math.min(OUTPUT_CAPTURE_SIDE, side),
  };
}

export function dataUrlToBase64(dataUrl: string): { base64: string; mimeType: string } {
  const match = /^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$/.exec(dataUrl);
  if (!match) throw new Error("CAPTURE_FAILED");
  if (match[1] !== "image/jpeg" && match[1] !== "image/png") throw new Error("CAPTURE_FAILED");
  return { mimeType: match[1], base64: match[2] };
}

export async function captureAvatar(video: HTMLVideoElement): Promise<CapturedAvatar> {
  const width = video.videoWidth;
  const height = video.videoHeight;
  if (!width || !height) throw new Error("CAPTURE_FAILED");
  const bounds = cropSquareBounds(width, height);
  const canvas = document.createElement("canvas");
  canvas.width = bounds.outputSide;
  canvas.height = bounds.outputSide;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("CAPTURE_FAILED");
  context.drawImage(video, bounds.sourceX, bounds.sourceY, bounds.sourceSide, bounds.sourceSide, 0, 0, bounds.outputSide, bounds.outputSide);
  let quality = 0.82;
  let dataUrl = canvas.toDataURL("image/jpeg", quality);
  let parsed = dataUrlToBase64(dataUrl);
  while (parsed.base64.length * 3 / 4 > MAX_CAPTURE_BYTES && quality > 0.45) {
    quality -= 0.08;
    dataUrl = canvas.toDataURL("image/jpeg", quality);
    parsed = dataUrlToBase64(dataUrl);
  }
  const bytes = Math.ceil(parsed.base64.length * 3 / 4);
  if (bytes > MAX_CAPTURE_BYTES) throw new Error("CAPTURE_FAILED");
  return { dataUrl, base64: parsed.base64, mimeType: "image/jpeg", width: bounds.outputSide, height: bounds.outputSide, bytes };
}
