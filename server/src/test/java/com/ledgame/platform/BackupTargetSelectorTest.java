package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTargetSelectorTest {
    @TempDir Path root;

    @Test
    void rejectsAnotherPartitionOnTheSamePhysicalDiskAndSelectsTrueCrossDisk() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Path sameDisk = Files.createDirectories(root.resolve("same-disk"));
        Path otherDisk = Files.createDirectories(root.resolve("other-disk"));
        Path database = Files.writeString(source.resolve("platform.db"), "db");
        List<DiskVolume> volumes = List.of(
                volume(source, "disk-a", 0, false, "Fixed", 1_000_000_000L),
                volume(sameDisk, "disk-a", 0, false, "Fixed", 1_000_000_000L),
                volume(otherDisk, "disk-b", 1, false, "Fixed", 1_000_000_000L));

        BackupTarget target = new BackupTargetSelector(1).select(database, volumes, Files.size(database)).orElseThrow();

        assertThat(target.volume().physicalIdentity()).isEqualTo("uid:disk-b");
        assertThat(target.root()).isEqualTo(otherDisk.resolve("LEDGameBackup").resolve("member-admin"));
        assertThat(target.root()).isDirectory();
    }

    @Test
    void testEnvironmentCanUseASeparateFixedNamespace() throws Exception {
        Path source = Files.createDirectories(root.resolve("source-test"));
        Path otherDisk = Files.createDirectories(root.resolve("other-test-disk"));
        Path database = Files.writeString(source.resolve("platform.db"), "db");
        List<DiskVolume> volumes = List.of(
                volume(source, "disk-a", 0, false, "Fixed", 1_000_000_000L),
                volume(otherDisk, "disk-b", 1, false, "Fixed", 1_000_000_000L));

        BackupTarget target = new BackupTargetSelector(1, "LEDGameBackupTest")
                .select(database, volumes, Files.size(database)).orElseThrow();

        assertThat(target.root()).isEqualTo(otherDisk.resolve("LEDGameBackupTest").resolve("member-admin"));
        assertThat(target.root()).isDirectory();
    }

    @Test
    void usesDatabaseLocationNotApplicationLocationAndSortsCandidatesDeterministically() throws Exception {
        Path databaseMount = Files.createDirectories(root.resolve("database-volume"));
        Path appMount = Files.createDirectories(root.resolve("application-volume"));
        Path first = Files.createDirectories(root.resolve("first-target"));
        Path second = Files.createDirectories(root.resolve("second-target"));
        Path database = Files.writeString(databaseMount.resolve("platform.db"), "db");
        List<DiskVolume> volumes = List.of(
                volume(appMount, "disk-app", 2, false, "Fixed", 1_000_000_000L),
                volume(databaseMount, "disk-source", 0, false, "Fixed", 1_000_000_000L),
                volume(second, "z-target", 4, false, "Fixed", 1_000_000_000L),
                volume(first, "a-target", 3, false, "Fixed", 1_000_000_000L));

        BackupTarget target = new BackupTargetSelector(1).select(database, volumes, 2).orElseThrow();

        assertThat(target.volume().uniqueId()).isEqualTo("a-target");
    }

    @Test
    void excludesReadOnlyNetworkRemovableAndLowSpaceVolumes() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Path database = Files.writeString(source.resolve("platform.db"), "db");
        List<DiskVolume> volumes = List.of(
                volume(source, "source", 0, false, "Fixed", 1_000_000_000L),
                volume(Files.createDirectories(root.resolve("readonly")), "readonly", 1, true, "Fixed", 1_000_000_000L),
                volume(Files.createDirectories(root.resolve("network")), "network", 2, false, "Network", 1_000_000_000L),
                new DiskVolume(Files.createDirectories(root.resolve("usb")), "usb", "", 3, "USB", "Fixed", "NTFS", false, 1_000_000_000L),
                volume(Files.createDirectories(root.resolve("small")), "small", 4, false, "Fixed", 10));

        assertThat(new BackupTargetSelector(100).select(database, volumes, Files.size(database))).isEmpty();
    }

    @Test
    void persistsPhysicalIdentityAndFindsTheSameDiskAfterItsMountPointChanges() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Path firstMount = Files.createDirectories(root.resolve("target-e"));
        Path changedMount = Files.createDirectories(root.resolve("target-f"));
        Path fallback = Files.createDirectories(root.resolve("fallback"));
        Path database = Files.writeString(source.resolve("platform.db"), "db");
        BackupTargetStateStore state = new BackupTargetStateStore(database, new ObjectMapper());
        state.write(volume(firstMount, "preferred-disk", 1, false, "Fixed", 1_000_000_000L)
                .persistentIdentity());

        List<DiskVolume> firstTopology = List.of(
                volume(source, "source", 0, false, "Fixed", 1_000_000_000L),
                volume(firstMount, "preferred-disk", 1, false, "Fixed", 1_000_000_000L));
        assertThat(new BackupTargetSelector(1).select(database, firstTopology, 2,
                state.readPreferredIdentity()).orElseThrow().volume().mountPoint()).isEqualTo(firstMount);

        List<DiskVolume> reconnectedTopology = List.of(
                volume(source, "source", 0, false, "Fixed", 1_000_000_000L),
                volume(fallback, "a-fallback", 2, false, "Fixed", 1_000_000_000L),
                volume(changedMount, "preferred-disk", 1, false, "Fixed", 1_000_000_000L));
        assertThat(new BackupTargetSelector(1).select(database, reconnectedTopology, 2,
                state.readPreferredIdentity()).orElseThrow().volume().mountPoint()).isEqualTo(changedMount);
    }

    private static DiskVolume volume(Path mount, String id, int number, boolean readOnly, String driveType, long free) {
        return new DiskVolume(mount, id, "", number, "NVMe", driveType, "NTFS", readOnly, free);
    }
}
