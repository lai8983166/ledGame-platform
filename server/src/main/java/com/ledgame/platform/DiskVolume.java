package com.ledgame.platform;

import java.nio.file.Path;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record DiskVolume(
        Path mountPoint,
        String uniqueId,
        String serialNumber,
        int diskNumber,
        String busType,
        String driveType,
        String fileSystem,
        boolean readOnly,
        long freeBytes) {

    public String physicalIdentity() {
        if (present(uniqueId)) return "uid:" + uniqueId.trim().toLowerCase(Locale.ROOT);
        if (present(serialNumber)) return "serial:" + serialNumber.trim().toLowerCase(Locale.ROOT);
        return "disk:" + diskNumber;
    }

    public String persistentIdentity() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(physicalIdentity().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public boolean localFixedVolume() {
        String drive = driveType == null ? "" : driveType.trim();
        String bus = busType == null ? "" : busType.trim().toUpperCase(Locale.ROOT);
        return "FIXED".equalsIgnoreCase(drive)
                && !readOnly
                && !bus.equals("USB")
                && !bus.equals("SD")
                && !bus.equals("MMC")
                && !bus.equals("UNKNOWN");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
