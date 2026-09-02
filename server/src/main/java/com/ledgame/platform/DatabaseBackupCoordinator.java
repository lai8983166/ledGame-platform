package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Order(Ordered.LOWEST_PRECEDENCE)
public class DatabaseBackupCoordinator implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseBackupCoordinator.class);
    private final DatabaseBackupProperties properties;
    private final DiskTopologyProvider topologyProvider;
    private final DatabaseBackupEngine engine;
    private final DatabaseFileInspector inspector;
    private final DatabaseStateService databaseState;
    private final StartupGate gate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ZoneId zoneId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "database-backup-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean backupRunning = new AtomicBoolean();
    private volatile BackupTarget target;
    private volatile long lastBackedRevision = -1;
    private volatile Instant lastSuccessfulBackupAt;
    private volatile long firstDirtyAt;
    private volatile long lastObservedDirtyAt;
    private volatile long nextRetryAt;
    private volatile DatabaseSourceFingerprint lastObservedFingerprint;
    private BackupTargetStateStore targetStateStore;
    private volatile BackupStatusSnapshot statusBeforeImport;
    private volatile BackupErrorCode lastTargetResolutionError = BackupErrorCode.NO_CROSS_DISK_TARGET;

    public DatabaseBackupCoordinator(
            DatabaseBackupProperties properties,
            DiskTopologyProvider topologyProvider,
            DatabaseBackupEngine engine,
            DatabaseFileInspector inspector,
            DatabaseStateService databaseState,
            StartupGate gate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${ledgame.time-zone:Asia/Shanghai}") String timeZone) {
        this.properties = properties;
        this.topologyProvider = topologyProvider;
        this.engine = engine;
        this.inspector = inspector;
        this.databaseState = databaseState;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.zoneId = ZoneId.of(timeZone);
    }

    @Override
    public void run(ApplicationArguments args) {
        targetStateStore = new BackupTargetStateStore(engine.sourceDatabase(), objectMapper);
        lastObservedFingerprint = DatabaseSourceFingerprint.capture(engine.sourceDatabase());
        initialize();
        long poll = Math.max(properties.getPollMillis(), 250);
        scheduler.scheduleWithFixedDelay(this::safeTick, poll, poll, TimeUnit.MILLISECONDS);
    }

    public BackupStatusSnapshot status() { return gate.status(); }
    public Path backupRoot() { return target == null ? null : target.root(); }

    public synchronized void beginImport() {
        BackupLifecycleState state = gate.status().state();
        if (state == BackupLifecycleState.CHECKING || state == BackupLifecycleState.BLOCKED) {
            throw new PlatformApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "DATABASE_IMPORT_NOT_READY", "数据库检查尚未完成，暂时不能导入");
        }
        statusBeforeImport = gate.status();
        BackupStatusSnapshot current = gate.status();
        gate.update(new BackupStatusSnapshot(BackupLifecycleState.IMPORTING, "IMPORT_PREPARED", false,
                current.targetVolume(), current.lastSuccessfulBackupAt(), current.sourceRevision(),
                current.backupRevision(), null, "正在准备导入数据库"));
    }

    public synchronized void cancelImport() {
        if (gate.status().state() == BackupLifecycleState.IMPORTING && statusBeforeImport != null) {
            gate.update(statusBeforeImport);
        }
        statusBeforeImport = null;
    }

    public boolean flush() {
        if (!properties.isEnabled()) return true;
        try {
            DatabaseStateSnapshot source = databaseState.current();
            if (source.revision() <= lastBackedRevision) return true;
            return backupNow(source);
        } catch (Exception exception) {
            return false;
        }
    }

    private void initialize() {
        if (!properties.isEnabled() || engine.inMemoryDatabase()) {
            DatabaseStateSnapshot source = databaseState.current();
            BackupErrorCode reason = properties.isEnabled()
                    ? BackupErrorCode.UNSUPPORTED_PLATFORM : BackupErrorCode.BACKUP_DISABLED;
            gate.update(StartupGate.degraded(reason, null, null,
                    source.revision(), null));
            return;
        }
        DatabaseStateSnapshot source;
        try {
            gate.update(StartupGate.checking("CHECKING_DATABASE", "正在检查主数据库", 0));
            InspectedDatabase main = inspector.inspect(engine.sourceDatabase());
            if (!main.valid()) {
                gate.update(StartupGate.blocked(BackupErrorCode.DATABASE_INTEGRITY_FAILED));
                return;
            }
            source = main.state();
        } catch (Exception exception) {
            gate.update(StartupGate.blocked(BackupErrorCode.DATABASE_INTEGRITY_FAILED));
            return;
        }
        gate.update(StartupGate.checking("CHECKING_TARGET", "正在检查异盘备份", source.revision()));
        Optional<BackupTarget> resolved = resolveTarget();
        if (resolved.isEmpty()) {
            gate.update(StartupGate.degraded(lastTargetResolutionError, null, null,
                    source.revision(), null));
            LOG.warn("database_backup_target_unavailable code={} sourceRevision={}",
                    lastTargetResolutionError, source.revision());
            return;
        }
        target = resolved.get();
        targetStateStore.write(target.volume().persistentIdentity());
        inspectOrCreateLatest(source);
    }

    private Optional<BackupTarget> resolveTarget() {
        try {
            if (properties.getRootOverride() != null && !properties.getRootOverride().isBlank()) {
                Path root = Path.of(properties.getRootOverride()).toAbsolutePath().normalize();
                Files.createDirectories(root);
                DiskVolume volume = new DiskVolume(root.getRoot() == null ? root : root.getRoot(),
                        "override", "", -1, "TEST", "Fixed", "", false, Long.MAX_VALUE);
                lastTargetResolutionError = null;
                return Optional.of(new BackupTarget(volume, root));
            }
            List<DiskVolume> volumes = topologyProvider.discover();
            long size = Files.exists(engine.sourceDatabase()) ? Files.size(engine.sourceDatabase()) : 1;
            String rootDirectoryName = "TEST".equals(properties.getEnvironment())
                    ? "LEDGameBackupTest" : "LEDGameBackup";
            BackupTargetSelector selector = new BackupTargetSelector(
                    properties.getMinimumFreeBytes(), rootDirectoryName);
            Optional<BackupTarget> selected = selector.select(
                    engine.sourceDatabase(), volumes, size, targetStateStore.readPreferredIdentity());
            if (selected.isPresent()) {
                lastTargetResolutionError = null;
                return selected;
            }
            DiskVolume source = selector.sourceVolume(engine.sourceDatabase(), volumes).orElse(null);
            List<DiskVolume> otherFixed = volumes.stream().filter(DiskVolume::localFixedVolume)
                    .filter(volume -> source != null
                            && !volume.physicalIdentity().equals(source.physicalIdentity())).toList();
            long required = Math.max(properties.getMinimumFreeBytes(), Math.max(size, 1L) * 3L);
            lastTargetResolutionError = otherFixed.isEmpty() ? BackupErrorCode.NO_CROSS_DISK_TARGET
                    : otherFixed.stream().noneMatch(volume -> volume.freeBytes() >= required)
                            ? BackupErrorCode.TARGET_SPACE_LOW : BackupErrorCode.TARGET_NOT_WRITABLE;
            return Optional.empty();
        } catch (Exception exception) {
            lastTargetResolutionError = String.valueOf(exception.getMessage())
                    .contains(BackupErrorCode.UNSUPPORTED_PLATFORM.name())
                    ? BackupErrorCode.UNSUPPORTED_PLATFORM : BackupErrorCode.DISK_TOPOLOGY_FAILED;
            LOG.warn("database_backup_topology_failed code={} message={}",
                    lastTargetResolutionError, exception.getMessage());
            return Optional.empty();
        }
    }

    private void inspectOrCreateLatest(DatabaseStateSnapshot source) {
        Path latest = target.root().resolve("latest/platform.db");
        if (!Files.exists(latest)) {
            backupNow(source);
            return;
        }
        try {
            InspectedDatabase backup = inspector.inspect(latest);
            DatabaseBackupMetadata metadata = engine.readLatestMetadata(target.root());
            if (!engine.acceptsMetadata(metadata)) {
                if (quarantineLatest("environment-mismatch")) backupNow(source);
                else gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_PUBLISH_FAILED,
                        targetVolume(), lastSuccessfulBackupAt, source.revision(), null));
                return;
            }
            if (!backup.valid() || !backup.sha256().equalsIgnoreCase(metadata.sha256())) {
                if (quarantineLatest("invalid")) backupNow(source);
                else gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_PUBLISH_FAILED,
                        targetVolume(), lastSuccessfulBackupAt, source.revision(), null));
                return;
            }
            BackupSyncRelation relation = DatabaseVersionComparator.compare(source, backup.state());
            if (relation == BackupSyncRelation.SYNCHRONIZED) {
                lastBackedRevision = backup.state().revision();
                lastSuccessfulBackupAt = metadata.generatedAt();
                gate.update(StartupGate.protectedStatus(targetVolume(), metadata.generatedAt(),
                        source.revision(), backup.state().revision()));
            } else if (relation == BackupSyncRelation.BACKUP_BEHIND) {
                backupNow(source);
            } else if (relation == BackupSyncRelation.BACKUP_AHEAD) {
                if (source.importedAt() != null && source.importedFromRevision() != null) {
                    if (quarantineLatest("replaced-by-import")) backupNow(source);
                    else gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_PUBLISH_FAILED,
                            targetVolume(), lastSuccessfulBackupAt, source.revision(), backup.state().revision()));
                } else {
                    gate.update(StartupGate.maintenance(BackupErrorCode.DATABASE_VERSION_CONFLICT,
                            targetVolume(), source.revision(), backup.state().revision()));
                }
            } else if (source.importedAt() != null && source.importedFromRevision() != null) {
                if (quarantineLatest("replaced-by-import")) backupNow(source);
                else gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_PUBLISH_FAILED,
                        targetVolume(), lastSuccessfulBackupAt, source.revision(), backup.state().revision()));
            } else {
                gate.update(StartupGate.maintenance(BackupErrorCode.DATABASE_IDENTITY_CONFLICT,
                        targetVolume(), source.revision(), backup.state().revision()));
            }
        } catch (Exception exception) {
            if (quarantineLatest("invalid")) backupNow(source);
            else gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_PUBLISH_FAILED,
                    targetVolume(), lastSuccessfulBackupAt, source.revision(), null));
        }
    }

    public synchronized BackupStatusSnapshot keepCurrentDatabase() {
        BackupStatusSnapshot current = gate.status();
        if (current.state() != BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED
                || !(BackupErrorCode.DATABASE_IDENTITY_CONFLICT.name().equals(current.errorCode())
                || BackupErrorCode.DATABASE_VERSION_CONFLICT.name().equals(current.errorCode()))) {
            throw new PlatformApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "DATABASE_CONFLICT_NOT_ACTIVE", "当前没有需要处理的数据库备份冲突");
        }
        if (!quarantineLatest("kept-current")) {
            throw new PlatformApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    BackupErrorCode.BACKUP_PUBLISH_FAILED.name(), "无法隔离冲突备份，当前数据库和备份均未被覆盖");
        }
        lastBackedRevision = -1;
        backupNow(databaseState.current());
        return gate.status();
    }

    private void safeTick() {
        try { tick(); } catch (Exception ignored) {}
    }

    private void tick() {
        if (!properties.isEnabled()) return;
        BackupLifecycleState lifecycle = gate.status().state();
        if (lifecycle == BackupLifecycleState.BLOCKED
                || lifecycle == BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED
                || lifecycle == BackupLifecycleState.IMPORTING) return;
        long now = System.currentTimeMillis();
        if (target != null && now < nextRetryAt) return;
        if (target == null && now >= nextRetryAt) {
            resolveTarget().ifPresent(found -> {
                target = found;
                targetStateStore.write(found.volume().persistentIdentity());
                inspectOrCreateLatest(databaseState.current());
            });
            if (target == null) {
                nextRetryAt = now + Math.max(properties.getRetryMillis(), 1000);
                return;
            }
        }
        DatabaseStateSnapshot source = databaseState.current();
        DatabaseSourceFingerprint fingerprint = DatabaseSourceFingerprint.capture(engine.sourceDatabase());
        boolean fileChanged = !fingerprint.equals(lastObservedFingerprint);
        if (fileChanged) {
            lastObservedFingerprint = fingerprint;
            if (firstDirtyAt == 0) firstDirtyAt = now;
            lastObservedDirtyAt = now;
        }
        boolean dailySnapshotDue = lastSuccessfulBackupAt == null
                || !LocalDate.ofInstant(lastSuccessfulBackupAt, zoneId)
                        .equals(LocalDate.ofInstant(clock.instant(), zoneId));
        if (source.revision() <= lastBackedRevision && firstDirtyAt == 0 && !dailySnapshotDue) {
            firstDirtyAt = 0;
            return;
        }
        if (firstDirtyAt == 0) firstDirtyAt = now;
        if (lastObservedDirtyAt == 0) lastObservedDirtyAt = now;
        boolean debounced = now - lastObservedDirtyAt >= properties.getDebounceMillis();
        boolean overdue = now - firstDirtyAt >= properties.getMaxDirtyMillis();
        // Polling sees a stable revision on the next tick; overdue is the hard upper bound.
        if (debounced || overdue || properties.getDebounceMillis() <= properties.getPollMillis()) {
            backupNow(source);
        }
    }

    private boolean backupNow(DatabaseStateSnapshot source) {
        if (target == null || !backupRunning.compareAndSet(false, true)) return false;
        try {
            BackupLifecycleState currentState = gate.status().state();
            BackupLifecycleState backupState = currentState == BackupLifecycleState.READY_PROTECTED
                    || currentState == BackupLifecycleState.READY_DEGRADED
                    ? currentState : BackupLifecycleState.CHECKING;
            gate.update(new BackupStatusSnapshot(backupState, "BACKING_UP", false,
                    targetVolume(), lastSuccessfulBackupAt, source.revision(),
                    lastBackedRevision < 0 ? null : lastBackedRevision, null, "正在同步数据库备份"));
            DatabaseBackupMetadata metadata = engine.backup(target.root(), target.volume().persistentIdentity());
            lastBackedRevision = metadata.revision();
            lastSuccessfulBackupAt = metadata.generatedAt();
            DatabaseStateSnapshot after = databaseState.current();
            DatabaseSourceFingerprint afterFingerprint = DatabaseSourceFingerprint.capture(engine.sourceDatabase());
            boolean changedDuringBackup = after.revision() > metadata.revision()
                    || !afterFingerprint.equals(lastObservedFingerprint);
            lastObservedFingerprint = afterFingerprint;
            firstDirtyAt = changedDuringBackup ? System.currentTimeMillis() : 0;
            lastObservedDirtyAt = changedDuringBackup ? firstDirtyAt : 0;
            gate.update(StartupGate.protectedStatus(targetVolume(), metadata.generatedAt(),
                    after.revision(), metadata.revision()));
            LOG.info("database_backup_succeeded sourceRevision={} backupRevision={} targetVolume={}",
                    after.revision(), metadata.revision(), targetVolume());
            return true;
        } catch (Exception exception) {
            nextRetryAt = System.currentTimeMillis() + Math.max(properties.getRetryMillis(), 1000);
            if (target != null && (!Files.isDirectory(target.root()) || !Files.isWritable(target.root()))) {
                target = null;
            }
            gate.update(StartupGate.degraded(errorFor(exception), targetVolume(), lastSuccessfulBackupAt,
                    source.revision(), lastBackedRevision < 0 ? null : lastBackedRevision));
            LOG.warn("database_backup_failed code={} sourceRevision={} message={}",
                    errorFor(exception), source.revision(), exception.getMessage());
            return false;
        } finally {
            backupRunning.set(false);
        }
    }

    private BackupErrorCode errorFor(Exception exception) {
        String message = String.valueOf(exception.getMessage());
        for (BackupErrorCode value : BackupErrorCode.values()) {
            if (message.contains(value.name())) return value;
        }
        return BackupErrorCode.ONLINE_BACKUP_FAILED;
    }

    private boolean quarantineLatest(String reason) {
        if (target == null) return false;
        Path latestDirectory = target.root().resolve("latest");
        Path latestDatabase = latestDirectory.resolve("platform.db");
        Path latestMetadata = latestDirectory.resolve("metadata.json");
        if (!Files.exists(latestDatabase) && !Files.exists(latestMetadata)) return true;
        Path archive = target.root().resolve("quarantine").resolve(
                reason + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(java.time.ZoneOffset.UTC).format(clock.instant()));
        Path archivedDatabase = archive.resolve("platform.db");
        Path archivedMetadata = archive.resolve("metadata.json");
        try {
            Files.createDirectories(archive);
            if (Files.exists(latestDatabase)) {
                Files.move(latestDatabase, archivedDatabase, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                if (Files.exists(latestMetadata)) {
                    Files.move(latestMetadata, archivedMetadata, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception metadataFailure) {
                if (Files.exists(archivedDatabase)) {
                    Files.move(archivedDatabase, latestDatabase, StandardCopyOption.REPLACE_EXISTING);
                }
                throw metadataFailure;
            }
            LOG.warn("database_backup_quarantined reason={} archive={}", reason, archive);
            return true;
        } catch (Exception exception) {
            LOG.warn("database_backup_quarantine_failed reason={} message={}", reason, exception.getMessage());
            return false;
        }
    }

    private String targetVolume() {
        if (target == null) return null;
        return target.volume().mountPoint().toString();
    }

    @PreDestroy
    public void close() {
        flush();
        scheduler.shutdownNow();
    }
}
