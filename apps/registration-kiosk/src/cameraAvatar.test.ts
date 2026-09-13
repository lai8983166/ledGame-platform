import { describe, expect, it } from "vitest";
import { cameraErrorCode, cropSquareBounds, dataUrlToBase64, MAX_CAPTURE_BYTES } from "./cameraAvatar";

describe("registration camera avatar capture", () => {
  it("crops landscape and portrait frames from the center into a bounded square", () => {
    expect(cropSquareBounds(1280, 720)).toEqual({ sourceX: 280, sourceY: 0, sourceSide: 720, outputSide: 512 });
    expect(cropSquareBounds(720, 1280)).toEqual({ sourceX: 0, sourceY: 280, sourceSide: 720, outputSide: 512 });
  });

  it("accepts only a base64 data URL and exposes no local file path", () => {
    expect(dataUrlToBase64("data:image/jpeg;base64,SGVsbG8=")).toEqual({ mimeType: "image/jpeg", base64: "SGVsbG8=" });
    expect(() => dataUrlToBase64("data:image/gif;base64,SGVsbG8=")).toThrow("CAPTURE_FAILED");
    expect(() => dataUrlToBase64("C:\\Users\\photo.jpg")).toThrow("CAPTURE_FAILED");
    expect(MAX_CAPTURE_BYTES).toBe(256 * 1024);
  });

  it("maps common Windows media failures to recoverable user-facing categories", () => {
    expect(cameraErrorCode({ name: "NotAllowedError" })).toBe("PERMISSION_DENIED");
    expect(cameraErrorCode({ name: "NotFoundError" })).toBe("NO_CAMERA");
    expect(cameraErrorCode({ name: "NotReadableError" }, { availableDeviceCount: 0 })).toBe("NO_CAMERA");
    expect(cameraErrorCode({ name: "NotReadableError" }, { availableDeviceCount: 1 })).toBe("BUSY");
    expect(cameraErrorCode({ name: "AbortError" })).toBe("BUSY");
    expect(cameraErrorCode({ name: "OverconstrainedError" })).toBe("NO_CAMERA");
    expect(cameraErrorCode({ message: "CAPTURE_FAILED" })).toBe("CAPTURE_FAILED");
  });
});
