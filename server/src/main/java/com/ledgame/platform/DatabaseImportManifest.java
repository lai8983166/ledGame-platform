package com.ledgame.platform;

public record DatabaseImportManifest(
        String preparedDatabasePath,
        String preparedKeyEnvelopePath,
        String keyEnvelopeSha256,
        String sha256,
        String preparedAvatarDirectoryPath,
        String preparedAvatarManifestPath,
        String avatarManifestSha256) {
    public DatabaseImportManifest(String preparedDatabasePath, String preparedKeyEnvelopePath,
            String keyEnvelopeSha256, String sha256) {
        this(preparedDatabasePath, preparedKeyEnvelopePath, keyEnvelopeSha256, sha256, null, null, null);
    }
}
