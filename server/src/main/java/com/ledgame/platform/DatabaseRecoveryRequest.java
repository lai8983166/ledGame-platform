package com.ledgame.platform;

import java.time.Instant;

/** Minimal customer-side artifact sent to the vendor; it contains no database or plaintext data. */
public record DatabaseRecoveryRequest(
        String format,
        String requestId,
        String recoveryKeyId,
        String instanceId,
        long revision,
        String keyId,
        String databaseSha256,
        String metadataSha256,
        String recoveryEnvelopeSha256,
        String temporaryPublicKey,
        Instant createdAt,
        Instant expiresAt) {
    public static final String FORMAT = "ledgame-database-recovery-request-v1";
}
