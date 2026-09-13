package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseBackupEnvironmentIsolationTest {
    @TempDir Path root;

    @Test
    void testAndProductionUseDifferentBackupRootsOnTheSameTargetVolume() throws Exception {
        Path sourceMount = Files.createDirectories(root.resolve("disk-a"));
        Path targetMount = Files.createDirectories(root.resolve("disk-b"));
        Path database = Files.writeString(sourceMount.resolve("platform.db"), "sqlite");
        List<DiskVolume> volumes = List.of(
                volume(sourceMount, "source"), volume(targetMount, "target"));

        BackupTarget production = new BackupTargetSelector(1, "LEDGameBackup")
                .select(database, volumes, Files.size(database)).orElseThrow();
        BackupTarget test = new BackupTargetSelector(1, "LEDGameBackupTest")
                .select(database, volumes, Files.size(database)).orElseThrow();

        assertThat(production.root()).isEqualTo(targetMount.resolve("LEDGameBackup/member-admin"));
        assertThat(test.root()).isEqualTo(targetMount.resolve("LEDGameBackupTest/member-admin"));
        assertThat(test.root()).isNotEqualTo(production.root());
    }

    private static DiskVolume volume(Path mount, String id) {
        return new DiskVolume(mount, id, "", id.equals("source") ? 1 : 2,
                "NVMe", "Fixed", "NTFS", false, 1_000_000);
    }
}
