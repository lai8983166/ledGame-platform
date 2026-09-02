package com.ledgame.platform;

import java.time.Instant;

public record DatabaseImportManifest(
        String candidateId,
        String preparedDatabasePath,
        String sha256,
        String instanceId,
        long revision,
        long importedFromRevision,
        Instant lastBusinessModifiedAt,
        Instant preparedAt) {}
