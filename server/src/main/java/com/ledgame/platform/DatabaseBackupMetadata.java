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
        String integrityCheck,
        String encryptionVersion,
        String keyId,
        String recoveryKeyId,
        String recoveryEnvelopeFormat,
        String recoveryEnvelopeSha256) {

    /** Compatibility constructor for metadata written before vendor recovery was added. */
    public DatabaseBackupMetadata(
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
            String integrityCheck,
            String encryptionVersion,
            String keyId) {
        this(format, environment, schemaVersion, instanceId, revision, lastBusinessModifiedAt,
                importedFromRevision, importedAt, generatedAt, sourceDatabase, targetDiskIdentity,
                fileSize, sha256, integrityCheck, encryptionVersion, keyId, null, null, null);
    }
}
