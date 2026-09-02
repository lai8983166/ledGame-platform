package com.ledgame.platform;

import java.time.Instant;

public record DatabaseStateSnapshot(
        String instanceId,
        long revision,
        Instant lastBusinessModifiedAt,
        Long importedFromRevision,
        Instant importedAt) {}
