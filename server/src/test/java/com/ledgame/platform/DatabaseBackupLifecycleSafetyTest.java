package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All disk topology and targets in this test are inside @TempDir, never real drives. */
class DatabaseBackupLifecycleSafetyTest {
    @TempDir Path root;

    @Test void memoryDatabaseCannotReachProductionTargetDuringPollingOrShutdown() throws Exception {
        excludedSource(true,false);
    }

    @Test void productionEphemeralDatabaseCannotReachTargetDuringPollingOrShutdown() throws Exception {
        excludedSource(false,true);
    }

    private void excludedSource(boolean memory,boolean ephemeral) throws Exception {
        var fixture = fixture(false,"other-store",5);
        when(fixture.engine.inMemoryDatabase()).thenReturn(memory);
        when(fixture.engine.ephemeralDatabase()).thenReturn(ephemeral);
        try {
            fixture.coordinator.run(null);
            assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_DEGRADED);
            verify(fixture.topology,after(850).never()).discover();
            fixture.coordinator.flush();
        } finally { fixture.coordinator.close(); }
        verify(fixture.engine,never()).backup(any(),anyString());
        verify(fixture.topology,never()).discover();
        assertThat(Files.readString(fixture.latest)).isEqualTo("original backup");
        assertThat(Files.readString(fixture.latest.resolveSibling("metadata.json"))).isEqualTo("original metadata");
    }

    @Test void identityConflictCannotBeOverwrittenByFlushOrClose() throws Exception {
        conflictShutdown("other-store",5,false);
    }

    @Test void newerBackupCannotBeOverwrittenByFlushOrClose() throws Exception {
        conflictShutdown("store",5,false);
    }

    @Test void recoveryMaintenanceCannotBeOverwrittenByFlushOrClose() throws Exception {
        conflictShutdown("store",4,true);
    }

    private void conflictShutdown(String instance,long revision,boolean recovery) throws Exception {
        var fixture = fixture(true,instance,revision);
        if (recovery) {
            when(fixture.engine.acceptsKeyEnvelope(any(),any())).thenReturn(false);
            Files.writeString(fixture.latest.resolveSibling("factory-key-envelope.json"),"original recovery envelope");
        }
        try {
            fixture.coordinator.run(null);
            assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);
            verify(fixture.engine,after(700).never()).backup(any(),anyString());
            assertThat(fixture.coordinator.flush()).isFalse();
            assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);
        } finally { fixture.coordinator.close(); }
        verify(fixture.engine,never()).backup(any(),anyString());
        assertThat(Files.readString(fixture.latest)).isEqualTo("original backup");
        assertThat(Files.readString(fixture.latest.resolveSibling("metadata.json"))).isEqualTo("original metadata");
    }

    @Test void targetRediscoveryStopsTheSameTickAfterDetectingConflict() throws Exception {
        var fixture = fixture(false,"other-store",5);
        try {
            fixture.coordinator.run(null);
            assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_DEGRADED);
            fixture.targetAvailable.set(true);
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (fixture.coordinator.status().state()!=BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED && System.nanoTime()<deadline) {
                Thread.sleep(20);
            }
            assertThat(fixture.coordinator.status().state()).isEqualTo(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED);
            verify(fixture.engine,after(400).never()).backup(any(),anyString());
        } finally { fixture.coordinator.close(); }
        verify(fixture.engine,never()).backup(any(),anyString());
        assertThat(Files.readString(fixture.latest)).isEqualTo("original backup");
    }

    @Test void importingBlockedAndCheckingStatesCannotFlush() throws Exception {
        for (var state : List.of(BackupLifecycleState.IMPORTING,BackupLifecycleState.BLOCKED,BackupLifecycleState.CHECKING)) {
            var fixture = fixture(true,"store",4);
            try {
                fixture.coordinator.run(null);
                clearInvocations(fixture.engine);
                fixture.gate.update(new BackupStatusSnapshot(state,"TEST",false,null,null,4,null,null,"test"));
                // Advance source after the initial backup so the old flush would run.
                when(fixture.databaseState.current()).thenReturn(snapshot("store",6));
                assertThat(fixture.coordinator.flush()).isFalse();
            } finally { fixture.coordinator.close(); }
            verify(fixture.engine,never()).backup(any(),anyString());
            assertThat(fixture.coordinator.status().state()).isEqualTo(state);
        }
    }

    private Fixture fixture(boolean initiallyAvailable,String backupInstance,long backupRevision) throws Exception {
        Path sourceMount = Files.createTempDirectory(root,"source-");
        Path targetMount = Files.createTempDirectory(root,"target-");
        Path source = Files.writeString(sourceMount.resolve("platform.db"),"main");
        Path latest = targetMount.resolve("LEDGameBackup/member-admin/latest/platform.db");
        Files.createDirectories(latest.getParent());
        Files.writeString(latest,"original backup");
        Files.writeString(latest.resolveSibling("metadata.json"),"original metadata");
        var sourceState = snapshot("store",4);
        var backupState = snapshot(backupInstance,backupRevision);
        var properties = new DatabaseBackupProperties();
        properties.setEnvironment("PRODUCTION");
        properties.setMinimumFreeBytes(1);
        properties.setPollMillis(250);
        properties.setDebounceMillis(0);
        var engine = mock(DatabaseBackupEngine.class);
        when(engine.sourceDatabase()).thenReturn(source);
        when(engine.acceptsMetadata(any())).thenReturn(true);
        when(engine.acceptsKeyEnvelope(any(),any())).thenReturn(true);
        var metadata = new DatabaseBackupMetadata(DatabaseBackupEngine.METADATA_FORMAT,"PRODUCTION",1,backupInstance,backupRevision,
                backupState.lastBusinessModifiedAt(),null,null,Instant.parse("2026-10-14T00:00:00Z"),source.toString(),"target",15,"hash","ok",
                ProtectedDataService.PLAINTEXT_VERSION,ProtectedDataService.PLAINTEXT_KEY_ID,"recovery-key",DatabaseRecoveryEnvelope.FORMAT,"hash");
        when(engine.readLatestMetadata(any())).thenReturn(metadata);
        var mainMetadata = new DatabaseBackupMetadata(DatabaseBackupEngine.METADATA_FORMAT,"PRODUCTION",1,"store",4,
                sourceState.lastBusinessModifiedAt(),null,null,Instant.now(),source.toString(),"target",15,"hash","ok",
                ProtectedDataService.PLAINTEXT_VERSION,ProtectedDataService.PLAINTEXT_KEY_ID);
        when(engine.backup(any(),anyString())).thenAnswer(invocation -> {
            Files.writeString(latest,"overwritten backup");
            return mainMetadata;
        });
        var inspector = mock(DatabaseFileInspector.class);
        when(inspector.inspect(source)).thenReturn(new InspectedDatabase(source,sourceState,4,"main",1,true,"ok"));
        when(inspector.inspect(latest)).thenReturn(new InspectedDatabase(latest,backupState,15,"hash",1,true,"ok"));
        var databaseState = mock(DatabaseStateService.class);
        when(databaseState.current()).thenReturn(sourceState);
        var available = new AtomicBoolean(initiallyAvailable);
        var topology = mock(DiskTopologyProvider.class);
        var sourceVolume = new DiskVolume(sourceMount,"source","",0,"NVMe","Fixed","NTFS",false,1_000_000);
        var targetVolume = new DiskVolume(targetMount,"target","",1,"NVMe","Fixed","NTFS",false,1_000_000);
        when(topology.discover()).thenAnswer(invocation -> available.get()?List.of(sourceVolume,targetVolume):List.of(sourceVolume));
        var gate = new StartupGate();
        var coordinator = new DatabaseBackupCoordinator(properties,topology,engine,inspector,databaseState,gate,
                new ObjectMapper().findAndRegisterModules(),Clock.systemUTC(),"Asia/Shanghai");
        return new Fixture(coordinator,engine,topology,databaseState,gate,available,latest);
    }

    private DatabaseStateSnapshot snapshot(String instance,long revision) {
        return new DatabaseStateSnapshot(instance,revision,Instant.parse("2026-10-14T00:00:00Z"),null,null);
    }

    private record Fixture(DatabaseBackupCoordinator coordinator,DatabaseBackupEngine engine,DiskTopologyProvider topology,
            DatabaseStateService databaseState,StartupGate gate,AtomicBoolean targetAvailable,Path latest) { }
}
