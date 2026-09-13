package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DatabaseImportService {
    private final DatabaseBackupCoordinator coordinator;
    private final DatabaseBackupEngine engine;
    private final DatabaseFileInspector inspector;
    private final DatabaseBackupProperties properties;
    private final DatabaseStateService databaseState;
    private final RoomConnectionRegistry rooms;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProtectedDataService protectedData;
    private final DataProtectionKeyManager dataKeys;
    private final Map<String, RegisteredCandidate> candidates = new ConcurrentHashMap<>();

    public DatabaseImportService(
            DatabaseBackupCoordinator coordinator,
            DatabaseBackupEngine engine,
            DatabaseFileInspector inspector,
            DatabaseBackupProperties properties,
            DatabaseStateService databaseState,
            RoomConnectionRegistry rooms,
            ObjectMapper objectMapper,
            Clock clock,
            ProtectedDataService protectedData,
            DataProtectionKeyManager dataKeys) {
        this.coordinator = coordinator;
        this.engine = engine;
        this.inspector = inspector;
        this.properties = properties;
        this.databaseState = databaseState;
        this.rooms = rooms;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.protectedData = protectedData;
        this.dataKeys = dataKeys;
    }

    public List<DatabaseBackupCandidate> discoverFixedCandidates() {
        candidates.entrySet().removeIf(entry -> !"EXTERNAL".equals(entry.getValue().sourceType()));
        Path root = coordinator.backupRoot();
        if (root == null) return List.of();
        List<DatabaseBackupCandidate> result = new ArrayList<>();
        addFixed(result, root.resolve("latest/platform.db"), root.resolve("latest/metadata.json"), "LATEST");
        Path history = root.resolve("history");
        if (Files.isDirectory(history)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(history, "*-platform.db")) {
                for (Path database : stream) {
                    Path metadata = Path.of(database.toString().replace("-platform.db", "-platform.json"));
                    addFixed(result, database, metadata, "HISTORY");
                }
            } catch (Exception ignored) {
                // Other valid candidates remain available and the UI can retry discovery.
            }
        }
        result.sort(Comparator.comparing(DatabaseBackupCandidate::generatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public DatabaseBackupCandidate registerExternal(Path rawPath) {
        Path path = rawPath.toAbsolutePath().normalize();
        InspectedDatabase inspected = requireValid(path);
        ImportSummary summary = requireImportSummary(path);
        String id = UUID.randomUUID().toString();
        DatabaseBackupMetadata metadata = readAdjacentMetadata(path);
        Path keyEnvelope = adjacentKeyEnvelope(path, metadata);
        RegisteredCandidate registered = new RegisteredCandidate(path, "EXTERNAL", metadata, keyEnvelope,
                avatarDirectory(path, "EXTERNAL"), avatarManifest(path, "EXTERNAL"));
        if (metadata != null) verifyMetadata(registered, inspected);
        candidates.put(id, registered);
        return toCandidate(id, registered, inspected, summary);
    }

    public DatabaseImportManifest prepare(String candidateId) {
        RegisteredCandidate registered = requireRegistered(candidateId);
        InspectedDatabase inspected = requireValid(registered.path());
        requireImportSummary(registered.path());
        ProtectedDataService candidateProtection = verifyMetadata(registered, inspected);
        verifyDataProtection(registered.path(), registered.metadata(), candidateProtection);
        if (rooms.hasActiveBusiness()) {
            throw new PlatformApiException(HttpStatus.CONFLICT, BackupErrorCode.IMPORT_BUSINESS_ACTIVE.name(),
                    BackupErrorCode.IMPORT_BUSINESS_ACTIVE.defaultMessage());
        }
        DatabaseStateSnapshot current = databaseState.current();
        long importedFromRevision = inspected.state().revision();
        Long observedBackupRevision = coordinator.status().backupRevision();
        long nextRevision = Math.max(Math.max(current.revision(), importedFromRevision),
                observedBackupRevision == null ? -1 : observedBackupRevision) + 1;
        Instant preparedAt = clock.instant();
        Path stagingDirectory = engine.sourceDatabase().getParent().resolve("import-staging");
        Path prepared = stagingDirectory.resolve("prepared-" + UUID.randomUUID() + ".db");
        Path preparedKey = stagingDirectory.resolve(prepared.getFileName() + ".data-key.dpapi");
        Path preparedAvatars = stagingDirectory.resolve(prepared.getFileName() + ".avatars");
        Path preparedAvatarManifest = stagingDirectory.resolve(prepared.getFileName() + ".avatar-manifest.json");
        try {
            Files.createDirectories(stagingDirectory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory, "prepared-*")) {
                for (Path stale : stream) deleteTree(stale);
            }
            Files.copy(registered.path(), prepared, StandardCopyOption.REPLACE_EXISTING);
            copyAvatarBundle(registered.avatarDirectory(), registered.avatarManifest(), preparedAvatars, preparedAvatarManifest);
            String keyEnvelopeSha256 = null;
            if (registered.keyEnvelope() != null && Files.isRegularFile(registered.keyEnvelope())) {
                Files.copy(registered.keyEnvelope(), preparedKey, StandardCopyOption.REPLACE_EXISTING);
                keyEnvelopeSha256 = inspector.sha256(preparedKey);
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + prepared);
                 var statement = connection.prepareStatement("""
                     UPDATE database_state
                        SET revision=?, imported_from_revision=?, imported_at=?
                      WHERE id=1
                     """)) {
                statement.setLong(1, nextRevision);
                statement.setLong(2, importedFromRevision);
                statement.setString(3, preparedAt.toString());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("database_state missing");
            }
            InspectedDatabase preparedInspection = requireValid(prepared);
            coordinator.beginImport();
            return new DatabaseImportManifest(prepared.toString(),
                    Files.isRegularFile(preparedKey) ? preparedKey.toString() : null,
                    keyEnvelopeSha256, preparedInspection.sha256(),
                    Files.isDirectory(preparedAvatars) ? preparedAvatars.toString() : null,
                    Files.isRegularFile(preparedAvatarManifest) ? preparedAvatarManifest.toString() : null,
                    Files.isRegularFile(preparedAvatarManifest) ? inspector.sha256(preparedAvatarManifest) : null);
        } catch (PlatformApiException exception) {
            deleteQuietly(prepared);
            deleteQuietly(preparedKey);
            deleteTree(preparedAvatars);
            deleteQuietly(preparedAvatarManifest);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(prepared);
            deleteQuietly(preparedKey);
            deleteTree(preparedAvatars);
            deleteQuietly(preparedAvatarManifest);
            throw new PlatformApiException(HttpStatus.INTERNAL_SERVER_ERROR, BackupErrorCode.IMPORT_FAILED.name(),
                    BackupErrorCode.IMPORT_FAILED.defaultMessage());
        }
    }

    public void cancelImport() {
        coordinator.cancelImport();
    }

    private void addFixed(List<DatabaseBackupCandidate> output, Path database, Path metadataPath, String sourceType) {
        if (!Files.isRegularFile(database)) return;
        try {
            DatabaseBackupMetadata metadata = Files.isRegularFile(metadataPath)
                    ? objectMapper.readValue(metadataPath.toFile(), DatabaseBackupMetadata.class) : null;
            if (!metadataMatchesEnvironment(metadata)) return;
            InspectedDatabase inspected = requireValid(database);
            ImportSummary summary = requireImportSummary(database);
            Path keyEnvelope = "LATEST".equals(sourceType)
                    ? database.getParent().resolve("data-key.dpapi")
                    : Path.of(database.toString().replace("-platform.db", "-data-key.dpapi"));
            RegisteredCandidate registered = new RegisteredCandidate(database, sourceType, metadata, keyEnvelope,
                    avatarDirectory(database, sourceType), avatarManifest(database, sourceType));
            verifyMetadata(registered, inspected);
            String id = UUID.randomUUID().toString();
            candidates.put(id, registered);
            output.add(toCandidate(id, registered, inspected, summary));
        } catch (Exception ignored) {
            // Invalid candidates must never reach the confirmation step.
        }
    }

    private DatabaseBackupCandidate toCandidate(
            String id, RegisteredCandidate registered, InspectedDatabase inspected, ImportSummary summary) {
        Instant generatedAt = registered.metadata() == null
                ? modified(registered.path()) : registered.metadata().generatedAt();
        return new DatabaseBackupCandidate(id, registered.sourceType(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), generatedAt, inspected.fileSize(),
                registered.metadata() == null ? "EXTERNAL" : registered.metadata().environment(),
                summary.factoryAdminUsername(), summary.memberCount(), true);
    }

    private RegisteredCandidate requireRegistered(String id) {
        RegisteredCandidate registered = id == null ? null : candidates.get(id);
        if (registered == null) invalid();
        return registered;
    }

    private InspectedDatabase requireValid(Path path) {
        try {
            InspectedDatabase inspected = inspector.inspect(path);
            if (!inspected.valid() || inspected.schemaVersion() > PlatformSchemaMigration.CURRENT_SCHEMA_VERSION) invalid();
            return inspected;
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (Exception exception) {
            invalid();
            return null;
        }
    }

    private ProtectedDataService verifyMetadata(RegisteredCandidate registered, InspectedDatabase inspected) {
        DatabaseBackupMetadata metadata = registered.metadata();
        if (metadata == null) return protectedData;
        boolean valid = DatabaseBackupEngine.METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment())
                && metadata.schemaVersion() == inspected.schemaVersion()
                && metadata.schemaVersion() <= PlatformSchemaMigration.CURRENT_SCHEMA_VERSION
                && metadata.revision() == inspected.state().revision()
                && metadata.instanceId().equals(inspected.state().instanceId())
                && metadata.fileSize() == inspected.fileSize()
                && metadata.sha256().equalsIgnoreCase(inspected.sha256())
                && ProtectedDataService.ENCRYPTION_VERSION.equals(metadata.encryptionVersion())
                && metadata.keyId() != null && !metadata.keyId().isBlank();
        ProtectedDataService candidateProtection = null;
        if (valid && dataKeys.requiresProtectedEnvelope()) {
            try {
                DataKeyMaterial material = dataKeys.loadEnvelope(registered.keyEnvelope());
                valid = metadata.keyId().equals(material.keyId());
                candidateProtection = ProtectedDataService.forKey(material);
            } catch (RuntimeException exception) {
                valid = false;
            }
        } else if (valid) {
            valid = metadata.keyId().equals(protectedData.keyId());
            candidateProtection = protectedData;
        }
        if (!valid) invalid();
        return candidateProtection;
    }

    private boolean metadataMatchesEnvironment(DatabaseBackupMetadata metadata) {
        return metadata != null
                && DatabaseBackupEngine.METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment())
                && ProtectedDataService.ENCRYPTION_VERSION.equals(metadata.encryptionVersion())
                && metadata.keyId() != null && !metadata.keyId().isBlank();
    }

    private void verifyDataProtection(
            Path path, DatabaseBackupMetadata metadata, ProtectedDataService candidateProtection) {
        try {
            org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
                 var state = connection.prepareStatement(
                         "SELECT format_version, key_id, status FROM data_protection_state WHERE id=1");
                 ResultSet stateRows = state.executeQuery()) {
                if (!stateRows.next()
                        || stateRows.getInt("format_version") != DataProtectionMigration.FORMAT_VERSION
                        || !"COMPLETE".equals(stateRows.getString("status"))
                        || !candidateProtection.keyId().equals(stateRows.getString("key_id"))) invalid();
                if (metadata != null && !stateRows.getString("key_id").equals(metadata.keyId())) invalid();
                verifyColumn(connection, candidateProtection, "members", "phone");
                verifyColumn(connection, candidateProtection, "members", "name");
                verifyColumn(connection, candidateProtection, "members", "avatar_id");
                verifyColumn(connection, candidateProtection, "members", "birthday");
                verifyColumn(connection, candidateProtection, "members", "gender");
                verifyColumn(connection, candidateProtection, "wristbands", "card_uid");
                verifyColumn(connection, candidateProtection, "wristband_charge_records", "wristband_uid");
                verifyColumn(connection, candidateProtection, "game_play_records", "wristband_uid");
                verifyColumn(connection, candidateProtection, "game_play_records", "result_json");
                verifyColumn(connection, candidateProtection, "operator_action_logs", "operator_username");
                verifyColumn(connection, candidateProtection, "operator_action_logs", "operator_display_name");
                verifyColumn(connection, candidateProtection, "operator_action_logs", "target_id");
                verifyColumn(connection, candidateProtection, "operator_action_logs", "summary_json");
            }
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (Exception exception) {
            invalid();
        }
    }

    private void verifyColumn(java.sql.Connection connection, ProtectedDataService candidateProtection,
            String table, String column) throws Exception {
        try (var plaintext = connection.createStatement();
             var rows = plaintext.executeQuery("SELECT COUNT(*) FROM " + table
                     + " WHERE " + column + " IS NOT NULL AND " + column + " NOT LIKE 'enc:v1:%'")) {
            if (rows.next() && rows.getLong(1) != 0) invalid();
        }
        try (var statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL LIMIT 50");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) candidateProtection.decryptField(table, column, rows.getString(1));
        }
    }

    private DatabaseBackupMetadata readAdjacentMetadata(Path database) {
        Path metadata = database.getFileName().toString().equals("platform.db")
                ? database.resolveSibling("metadata.json")
                : Path.of(database.toString().replace("-platform.db", "-platform.json"));
        if (!Files.isRegularFile(metadata)) return null;
        try { return objectMapper.readValue(metadata.toFile(), DatabaseBackupMetadata.class); }
        catch (Exception exception) { invalid(); return null; }
    }

    private Path adjacentKeyEnvelope(Path database, DatabaseBackupMetadata metadata) {
        if (metadata == null) return null;
        return database.getFileName().toString().equals("platform.db")
                ? database.resolveSibling("data-key.dpapi")
                : Path.of(database.toString().replace("-platform.db", "-data-key.dpapi"));
    }

    private Path avatarDirectory(Path database, String sourceType) {
        if ("LATEST".equals(sourceType) || "EXTERNAL".equals(sourceType)) return database.getParent().resolve("avatars");
        return Path.of(database.toString().replace("-platform.db", "-avatars"));
    }

    private Path avatarManifest(Path database, String sourceType) {
        if ("LATEST".equals(sourceType) || "EXTERNAL".equals(sourceType)) return database.getParent().resolve("avatar-manifest.json");
        return Path.of(database.toString().replace("-platform.db", "-avatar-manifest.json"));
    }

    private void copyAvatarBundle(Path sourceDirectory, Path sourceManifest, Path targetDirectory, Path targetManifest)
            throws Exception {
        if (!Files.isDirectory(sourceDirectory)) return;
        Files.createDirectories(targetDirectory);
        try (var stream = Files.list(sourceDirectory)) {
            for (Path source : stream.toList()) {
                if (!Files.isRegularFile(source) || !source.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.bin")) invalid();
                Files.copy(source, targetDirectory.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.isRegularFile(sourceManifest)) {
            Files.copy(sourceManifest, targetManifest, StandardCopyOption.REPLACE_EXISTING);
            if (!objectMapper.readTree(targetManifest.toFile()).path("format").asText().equals(AvatarBackupManifest.FORMAT)) invalid();
            verifyAvatarManifest(targetDirectory, targetManifest);
        }
    }

    private void verifyAvatarManifest(Path directory, Path manifestPath) throws Exception {
        AvatarBackupManifest manifest = objectMapper.readValue(manifestPath.toFile(), AvatarBackupManifest.class);
        for (AvatarBackupManifest.Entry entry : manifest.files()) {
            Path file = directory.resolve(entry.name()).normalize();
            if (!file.getParent().equals(directory.toAbsolutePath().normalize()) || !Files.isRegularFile(file)
                    || Files.size(file) != entry.size() || !inspector.sha256(file).equalsIgnoreCase(entry.sha256())) invalid();
        }
    }

    private ImportSummary requireImportSummary(Path path) {
        try {
            org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
                 var factory = connection.prepareStatement("""
                     SELECT username FROM operator_accounts
                      WHERE account_type='FACTORY_ADMIN' AND enabled=1 AND deleted_at IS NULL ORDER BY id
                     """);
                 ResultSet factoryRows = factory.executeQuery()) {
                String username = factoryRows.next() ? factoryRows.getString("username") : null;
                if (username == null || username.isBlank() || factoryRows.next()) invalidFactoryAccount();
                long memberCount;
                try (var count = connection.createStatement();
                     ResultSet rows = count.executeQuery("SELECT COUNT(*) FROM members")) {
                    memberCount = rows.next() ? rows.getLong(1) : 0;
                }
                return new ImportSummary(username, memberCount);
            }
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (Exception exception) {
            invalidFactoryAccount();
            return null;
        }
    }

    private static void invalidFactoryAccount() {
        throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                BackupErrorCode.IMPORT_FACTORY_ACCOUNT_INVALID.name(),
                BackupErrorCode.IMPORT_FACTORY_ACCOUNT_INVALID.defaultMessage());
    }

    private static void invalid() {
        throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                BackupErrorCode.IMPORT_CANDIDATE_INVALID.name(),
                BackupErrorCode.IMPORT_CANDIDATE_INVALID.defaultMessage());
    }

    private static Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (Exception exception) { return null; }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        } catch (Exception ignored) { }
    }

    private record RegisteredCandidate(
            Path path, String sourceType, DatabaseBackupMetadata metadata, Path keyEnvelope,
            Path avatarDirectory, Path avatarManifest) {}
    private record ImportSummary(String factoryAdminUsername, long memberCount) {}
}
