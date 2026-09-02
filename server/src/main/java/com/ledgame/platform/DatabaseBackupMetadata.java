package com.ledgame.platform;

import java.time.Instant;

public record DatabaseBackupMetadata(
        String format,
        String environment,
        int schemaVersion,
        String instanceId,
        long revision,
        Instant lastBusinessModifiedAt,
        Long importedFromRevision,
        Instant importedAt,
        Instant generatedAt,
        String sourceDatabase,
        String targetDiskIdentity,
        long fileSize,
        String sha256,
        String integrityCheck) {}
