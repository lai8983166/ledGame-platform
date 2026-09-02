package com.ledgame.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class BackupTargetSelector {
    private final long minimumFreeBytes;
    private final String rootDirectoryName;

    public BackupTargetSelector(long minimumFreeBytes) {
        this(minimumFreeBytes, "LEDGameBackup");
    }

    public BackupTargetSelector(long minimumFreeBytes, String rootDirectoryName) {
        this.minimumFreeBytes = minimumFreeBytes;
        this.rootDirectoryName = rootDirectoryName;
    }

    public Optional<BackupTarget> select(Path databasePath, List<DiskVolume> volumes, long databaseBytes) {
        return select(databasePath, volumes, databaseBytes, null);
    }

    public Optional<BackupTarget> select(
            Path databasePath, List<DiskVolume> volumes, long databaseBytes, String preferredIdentity) {
        DiskVolume source = sourceVolume(databasePath, volumes)
                .orElseThrow(() -> new IllegalStateException("SOURCE_DISK_NOT_FOUND"));
        long required = Math.max(minimumFreeBytes, Math.multiplyExact(Math.max(databaseBytes, 1L), 3L));
        return volumes.stream()
                .filter(DiskVolume::localFixedVolume)
                .filter(volume -> !volume.physicalIdentity().equals(source.physicalIdentity()))
                .filter(volume -> volume.freeBytes() >= required)
                .sorted(Comparator.comparingInt((DiskVolume volume) ->
                                volume.persistentIdentity().equals(preferredIdentity) ? 0 : 1)
                .thenComparing(DiskVolume::physicalIdentity)
                        .thenComparing(volume -> normalize(volume.mountPoint())))
                .map(volume -> new BackupTarget(volume,
                        volume.mountPoint().resolve(rootDirectoryName).resolve("member-admin")))
                .filter(this::writable)
                .findFirst();
    }

    public Optional<DiskVolume> sourceVolume(Path databasePath, List<DiskVolume> volumes) {
        Path normalized = databasePath.toAbsolutePath().normalize();
        return volumes.stream()
                .filter(volume -> normalized.startsWith(volume.mountPoint().toAbsolutePath().normalize()))
                .max(Comparator.comparingInt(volume -> normalize(volume.mountPoint()).length()));
    }

    private boolean writable(BackupTarget target) {
        Path probe = target.root().resolve(".write-probe-" + ProcessHandle.current().pid());
        try {
            Files.createDirectories(target.root());
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException exception) {
            try { Files.deleteIfExists(probe); } catch (IOException ignored) {}
            return false;
        }
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase();
    }
}
