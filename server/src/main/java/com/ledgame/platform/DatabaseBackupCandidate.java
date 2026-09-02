package com.ledgame.platform;

import java.time.Instant;

public record DatabaseBackupCandidate(
        String candidateId,
        String sourceType,
        long revision,
        Instant lastBusinessModifiedAt,
        Instant generatedAt,
        long fileSize,
        String environment,
        String factoryAdminUsername,
        long memberCount,
        boolean valid) {}
