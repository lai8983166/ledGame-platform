package com.ledgame.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.sqlite.SQLiteConfig;
import org.springframework.stereotype.Service;

@Service
public class DatabaseFileInspector {
    private static final Set<String> CORE_TABLES = Set.of(
            "members", "wristbands", "wristband_bindings", "game_play_records",
            "operator_accounts", "database_state");

    public InspectedDatabase inspect(Path rawPath) {
        Path path = rawPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Database file does not exist");
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties())) {
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only=ON");
                }
                String integrity;
                try (var statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                    integrity = resultSet.next() ? resultSet.getString(1) : "missing-result";
                }
                int schemaVersion;
                try (var statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                    schemaVersion = resultSet.next() ? resultSet.getInt(1) : 0;
                }
                int tables;
                try (var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM sqlite_master
                     WHERE type='table' AND name IN ('members','wristbands','wristband_bindings',
                                                     'game_play_records','operator_accounts','database_state')
                    """); ResultSet resultSet = statement.executeQuery()) {
                    tables = resultSet.next() ? resultSet.getInt(1) : 0;
                }
                DatabaseStateSnapshot state = null;
                if (tables == CORE_TABLES.size()) {
                    try (var statement = connection.prepareStatement("""
                        SELECT instance_id, revision, last_business_modified_at,
                               imported_from_revision, imported_at
                          FROM database_state WHERE id=1
                        """); ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            long importedRevision = resultSet.getLong("imported_from_revision");
                            boolean importedNull = resultSet.wasNull();
                            String importedAt = resultSet.getString("imported_at");
                            state = new DatabaseStateSnapshot(
                                    resultSet.getString("instance_id"),
                                    resultSet.getLong("revision"),
                                    Instant.parse(resultSet.getString("last_business_modified_at")),
                                    importedNull ? null : importedRevision,
                                    importedAt == null ? null : Instant.parse(importedAt));
                        }
                    }
                }
                return new InspectedDatabase(path, state, Files.size(path), sha256(path), schemaVersion,
                        tables == CORE_TABLES.size(), integrity);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to inspect SQLite database: " + path, exception);
        }
    }

    public String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
