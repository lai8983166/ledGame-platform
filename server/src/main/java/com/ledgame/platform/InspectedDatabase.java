package com.ledgame.platform;

import java.nio.file.Path;

public record InspectedDatabase(
        Path path,
        DatabaseStateSnapshot state,
        long fileSize,
        String sha256,
        int schemaVersion,
        boolean platformSchema,
        String integrityResult) {
    public boolean valid() {
        return platformSchema && schemaVersion >= 1
                && "ok".equalsIgnoreCase(integrityResult) && state != null;
    }
}
