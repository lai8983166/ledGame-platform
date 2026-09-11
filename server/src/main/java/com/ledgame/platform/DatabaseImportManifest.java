package com.ledgame.platform;

public record DatabaseImportManifest(
        String preparedDatabasePath,
        String preparedKeyEnvelopePath,
        String keyEnvelopeSha256,
        String sha256) {}
