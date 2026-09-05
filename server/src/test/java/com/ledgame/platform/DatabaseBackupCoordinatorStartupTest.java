package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class DatabaseBackupCoordinatorStartupTest {
    @TempDir Path root;

    @Test
    void synchronizedIdentityAndRevisionEnterProtectedMode() throws Exception {
        Fixture fixture = fixture(state("store-a", 4), state("store-a", 4), true);
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        fixture.close();
    }

    @Test
    void mainAheadAfterAnAbruptStopIsCaughtUpBeforeBusinessBecomesReady() throws Exception {
        Fixture fixture = fixture(state("store-a", 5), state("store-a", 4), true);
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        assertThat(fixture.coordinator.status().sourceRevision()).isEqualTo(5);
        assertThat(fixture.coordinator.status().backupRevision()).isEqualTo(5);
        fixture.close();
    }

    @Test
    void backupAheadAndDifferentIdentityRequireFactoryMaintenance() throws Exception {
        Fixture ahead = fixture(state("store-a", 4), state("store-a", 5), true);
        ahead.start();
        assertThat(ahead.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);
        assertThat(ahead.coordinator.status().errorCode()).isEqualTo("DATABASE_VERSION_CONFLICT");
        ahead.close();

        Fixture identity = fixture(state("store-a", 4), state("store-b", 4), true);
        identity.start();
        assertThat(identity.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);
        assertThat(identity.coordinator.status().errorCode()).isEqualTo("DATABASE_IDENTITY_CONFLICT");
        identity.close();
    }

    @Test
    void invalidMainDatabaseBlocksStartupWithoutOverwritingBackup() throws Exception {
        Fixture fixture = fixture(state("store-a", 4), state("store-a", 4), false);
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.BLOCKED);
        assertThat(fixture.coordinator.status().errorCode()).isEqualTo("DATABASE_INTEGRITY_FAILED");
        fixture.close();
    }

    @Test
    void intentionalImportedDatabaseArchivesOldIdentityAndPublishesANewLatest() throws Exception {
        DatabaseStateSnapshot imported = new DatabaseStateSnapshot("restored-store", 11,
                Instant.parse("2026-09-01T00:00:00Z"), 3L, Instant.parse("2026-09-02T00:00:00Z"));
        Fixture fixture = fixture(imported, state("old-store", 10), true);
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        assertThat(fixture.coordinator.status().backupRevision()).isEqualTo(11);
        fixture.close();
    }

    @Test
    void productionStartupQuarantinesTestBackupInsteadOfEnteringIdentityConflict() throws Exception {
        Fixture fixture = fixture(state("store-a", 4), state("test-store", 9), true,
                "ledgame-platform-backup-v2", "TEST");
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        assertThat(fixture.backupRoot.resolve("latest/platform.db")).doesNotExist();
        try (var quarantined = Files.walk(fixture.backupRoot.resolve("quarantine"))) {
            assertThat(quarantined.anyMatch(path -> path.getFileName().toString().equals("platform.db"))).isTrue();
        }
        verify(fixture.engine).backup(any(), anyString());
        fixture.close();

        Fixture legacy = fixture(state("store-a", 4), state("legacy-store", 9), true,
                "ledgame-platform-backup-v1", null);
        legacy.start();
        assertThat(legacy.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        verify(legacy.engine).backup(any(), anyString());
        legacy.close();
    }

    @Test
    void factoryCanKeepCurrentDatabaseAndQuarantineConflictingLatest() throws Exception {
        Fixture fixture = fixture(state("store-a", 4), state("store-b", 7), true);
        fixture.start();
        assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);

        BackupStatusSnapshot resolved = fixture.coordinator.keepCurrentDatabase();

        assertThat(resolved.state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        assertThat(fixture.backupRoot.resolve("latest/platform.db")).doesNotExist();
        verify(fixture.engine).backup(any(), anyString());
        fixture.close();
    }

    @Test
    void lowSpaceTargetReportsActionableReasonWithoutBlockingBusiness() throws Exception {
        Path sourceDirectory = Files.createDirectories(root.resolve("low-space-source"));
        Path targetDirectory = Files.createDirectories(root.resolve("low-space-target"));
        Path source = Files.writeString(sourceDirectory.resolve("platform.db"), "main");
        DatabaseStateSnapshot sourceState = state("store-low-space", 4);
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setMinimumFreeBytes(10_000);
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.sourceDatabase()).thenReturn(source);
        when(engine.inMemoryDatabase()).thenReturn(false);
        DatabaseFileInspector inspector = mock(DatabaseFileInspector.class);
        when(inspector.inspect(source)).thenReturn(inspection(source, sourceState, true));
        DatabaseStateService stateService = mock(DatabaseStateService.class);
        when(stateService.current()).thenReturn(sourceState);
        StartupGate gate = new StartupGate();
        DatabaseBackupCoordinator coordinator = new DatabaseBackupCoordinator(properties,
                () -> java.util.List.of(
                        volume(sourceDirectory, "source-disk", 0, false, 1_000_000),
                        volume(targetDirectory, "target-disk", 1, false, 1)),
                engine, inspector, stateService, gate, new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(), "Asia/Shanghai");

        coordinator.run(new DefaultApplicationArguments(new String[0]));

        assertThat(coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_DEGRADED);
        assertThat(coordinator.status().errorCode()).isEqualTo("TARGET_SPACE_LOW");
        assertThat(coordinator.status().message()).contains("空间不足");
        assertThat(gate.businessReady()).isTrue();
        verify(engine, never()).backup(any(), anyString());
        properties.setEnabled(false);
        coordinator.close();
    }

    @Test
    void unwritableTargetReportsActionableReasonWithoutReplacingAnyLatest() throws Exception {
        Path sourceDirectory = Files.createDirectories(root.resolve("unwritable-source"));
        Path targetDirectory = Files.createDirectories(root.resolve("unwritable-target"));
        Files.writeString(targetDirectory.resolve("LEDGameBackup"), "blocks backup directory creation");
        Path source = Files.writeString(sourceDirectory.resolve("platform.db"), "main");
        DatabaseStateSnapshot sourceState = state("store-unwritable", 5);
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setMinimumFreeBytes(1);
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.sourceDatabase()).thenReturn(source);
        when(engine.inMemoryDatabase()).thenReturn(false);
        DatabaseFileInspector inspector = mock(DatabaseFileInspector.class);
        when(inspector.inspect(source)).thenReturn(inspection(source, sourceState, true));
        DatabaseStateService stateService = mock(DatabaseStateService.class);
        when(stateService.current()).thenReturn(sourceState);
        StartupGate gate = new StartupGate();
        DatabaseBackupCoordinator coordinator = new DatabaseBackupCoordinator(properties,
                () -> java.util.List.of(
                        volume(sourceDirectory, "source-disk", 0, false, 1_000_000),
                        volume(targetDirectory, "target-disk", 1, false, 1_000_000)),
                engine, inspector, stateService, gate, new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(), "Asia/Shanghai");

        coordinator.run(new DefaultApplicationArguments(new String[0]));

        assertThat(coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_DEGRADED);
        assertThat(coordinator.status().errorCode()).isEqualTo("TARGET_NOT_WRITABLE");
        assertThat(coordinator.status().message()).contains("不可写").contains("目录权限");
        assertThat(gate.businessReady()).isTrue();
        assertThat(targetDirectory.resolve("LEDGameBackup")).hasContent("blocks backup directory creation");
        verify(engine, never()).backup(any(), anyString());
        properties.setEnabled(false);
        coordinator.close();
    }

    private Fixture fixture(DatabaseStateSnapshot mainState, DatabaseStateSnapshot backupState, boolean mainValid)
            throws Exception {
        return fixture(mainState, backupState, mainValid, "ledgame-platform-backup-v2", "PRODUCTION");
    }

    private Fixture fixture(DatabaseStateSnapshot mainState, DatabaseStateSnapshot backupState, boolean mainValid,
            String format, String environment) throws Exception {
        Path source = Files.writeString(root.resolve("source-" + System.nanoTime() + ".db"), "main");
        Path backupRoot = root.resolve("backup-" + System.nanoTime());
        Path latest = backupRoot.resolve("latest/platform.db");
        Files.createDirectories(latest.getParent());
        Files.writeString(latest, "backup");
        Files.writeString(backupRoot.resolve("latest/metadata.json"), "metadata");
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setRootOverride(backupRoot.toString());
        properties.setPollMillis(60_000);
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.sourceDatabase()).thenReturn(source);
        when(engine.inMemoryDatabase()).thenReturn(false);
        DatabaseFileInspector inspector = mock(DatabaseFileInspector.class);
        when(inspector.inspect(source)).thenReturn(inspection(source, mainState, mainValid));
        when(inspector.inspect(latest)).thenReturn(inspection(latest, backupState, true));
        DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(format, environment, 1,
                backupState.instanceId(), backupState.revision(), backupState.lastBusinessModifiedAt(),
                backupState.importedFromRevision(), backupState.importedAt(), Instant.parse("2026-09-02T00:00:01Z"),
                source.toString(), "test", 6, "hash", "ok");
        when(engine.readLatestMetadata(any())).thenReturn(metadata);
        when(engine.acceptsMetadata(any())).thenAnswer(invocation -> {
            DatabaseBackupMetadata value = invocation.getArgument(0);
            return DatabaseBackupEngine.METADATA_FORMAT.equals(value.format())
                    && properties.getEnvironment().equals(value.environment());
        });
        DatabaseBackupMetadata mainMetadata = new DatabaseBackupMetadata("ledgame-platform-backup-v2", "PRODUCTION", 1,
                mainState.instanceId(), mainState.revision(), mainState.lastBusinessModifiedAt(),
                mainState.importedFromRevision(), mainState.importedAt(), Instant.parse("2026-09-02T00:00:02Z"),
                source.toString(), "override", 6, "hash", "ok");
        when(engine.backup(any(), anyString())).thenReturn(mainMetadata);
        DatabaseStateService stateService = mock(DatabaseStateService.class);
        when(stateService.current()).thenReturn(mainState);
        DatabaseBackupCoordinator coordinator = new DatabaseBackupCoordinator(properties,
                mock(DiskTopologyProvider.class), engine, inspector, stateService, new StartupGate(),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), "Asia/Shanghai");
        return new Fixture(coordinator, properties, engine, backupRoot);
    }

    private static InspectedDatabase inspection(Path path, DatabaseStateSnapshot state, boolean valid) {
        return new InspectedDatabase(path, state, 6, "hash", valid ? 1 : 0, valid, valid ? "ok" : "corrupt");
    }

    private static DatabaseStateSnapshot state(String instance, long revision) {
        return new DatabaseStateSnapshot(instance, revision, Instant.parse("2026-09-02T00:00:00Z"), null, null);
    }

    private static DiskVolume volume(
            Path mount, String id, int number, boolean readOnly, long freeBytes) {
        return new DiskVolume(mount, id, "", number, "NVMe", "Fixed", "NTFS", readOnly, freeBytes);
    }

    private record Fixture(DatabaseBackupCoordinator coordinator, DatabaseBackupProperties properties,
            DatabaseBackupEngine engine, Path backupRoot) {
        void start() { coordinator.run(new DefaultApplicationArguments(new String[0])); }
        void close() { properties.setEnabled(false); coordinator.close(); }
    }
}
