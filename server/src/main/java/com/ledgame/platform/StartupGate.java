package com.ledgame.platform;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class StartupGate {
    private final AtomicReference<BackupStatusSnapshot> status = new AtomicReference<>(
            new BackupStatusSnapshot(BackupLifecycleState.CHECKING, "STARTING", false,
                    null, null, 0, null, null, "正在启动本机服务"));

    public BackupStatusSnapshot status() { return status.get(); }
    public void update(BackupStatusSnapshot next) { status.set(next); }

    public boolean businessReady() {
        BackupLifecycleState state = status.get().state();
        return state == BackupLifecycleState.READY_PROTECTED
                || state == BackupLifecycleState.READY_DEGRADED;
    }

    public void phase(String phase, String message) {
        BackupStatusSnapshot current = status.get();
        update(new BackupStatusSnapshot(current.state(), phase, current.protectedData(),
                current.targetVolume(), current.lastSuccessfulBackupAt(), current.sourceRevision(),
                current.backupRevision(), current.errorCode(), message));
    }

    public static BackupStatusSnapshot checking(String phase, String message, long sourceRevision) {
        return new BackupStatusSnapshot(BackupLifecycleState.CHECKING, phase, false,
                null, null, sourceRevision, null, null, message);
    }

    public static BackupStatusSnapshot protectedStatus(
            String targetVolume, Instant lastSuccess, long sourceRevision, long backupRevision) {
        return new BackupStatusSnapshot(BackupLifecycleState.READY_PROTECTED, "COMPLETE", true,
                targetVolume, lastSuccess, sourceRevision, backupRevision, null, "数据库异盘备份正常");
    }

    public static BackupStatusSnapshot degraded(
            BackupErrorCode code, String targetVolume, Instant lastSuccess, long sourceRevision, Long backupRevision) {
        return new BackupStatusSnapshot(BackupLifecycleState.READY_DEGRADED, "COMPLETE", false,
                targetVolume, lastSuccess, sourceRevision, backupRevision, code.name(), code.defaultMessage());
    }

    public static BackupStatusSnapshot maintenance(
            BackupErrorCode code, String targetVolume, long sourceRevision, Long backupRevision) {
        return new BackupStatusSnapshot(BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED, "COMPLETE", false,
                targetVolume, null, sourceRevision, backupRevision, code.name(), code.defaultMessage());
    }

    public static BackupStatusSnapshot blocked(BackupErrorCode code) {
        return new BackupStatusSnapshot(BackupLifecycleState.BLOCKED, "BLOCKED", false,
                null, null, 0, null, code.name(), code.defaultMessage());
    }
}
