package com.ledgame.platform;

import java.time.Instant;

public record BackupStatusSnapshot(
        BackupLifecycleState state,
        String phase,
        boolean protectedData,
        String targetVolume,
        Instant lastSuccessfulBackupAt,
        long sourceRevision,
        Long backupRevision,
        String errorCode,
        String message) {}
