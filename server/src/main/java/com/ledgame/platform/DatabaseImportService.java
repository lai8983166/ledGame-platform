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
    private final Map<String, RegisteredCandidate> candidates = new ConcurrentHashMap<>();

    public DatabaseImportService(
            DatabaseBackupCoordinator coordinator,
            DatabaseBackupEngine engine,
            DatabaseFileInspector inspector,
            DatabaseBackupProperties properties,
            DatabaseStateService databaseState,
            RoomConnectionRegistry rooms,
            ObjectMapper objectMapper,
            Clock clock) {
        this.coordinator = coordinator;
        this.engine = engine;
        this.inspector = inspector;
        this.properties = properties;
        this.databaseState = databaseState;
        this.rooms = rooms;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        RegisteredCandidate registered = new RegisteredCandidate(path, "EXTERNAL", null);
        candidates.put(id, registered);
        return toCandidate(id, registered, inspected, summary);
    }

    public DatabaseImportManifest prepare(String candidateId) {
        RegisteredCandidate registered = requireRegistered(candidateId);
        InspectedDatabase inspected = requireValid(registered.path());
        requireImportSummary(registered.path());
        verifyMetadata(registered, inspected);
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
        try {
            Files.createDirectories(stagingDirectory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory, "prepared-*.db")) {
                for (Path stale : stream) Files.deleteIfExists(stale);
            }
            Files.copy(registered.path(), prepared, StandardCopyOption.REPLACE_EXISTING);
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
            return new DatabaseImportManifest(candidateId, prepared.toString(), preparedInspection.sha256(),
                    preparedInspection.state().instanceId(), preparedInspection.state().revision(),
                    importedFromRevision, preparedInspection.state().lastBusinessModifiedAt(), preparedAt);
        } catch (PlatformApiException exception) {
            deleteQuietly(prepared);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(prepared);
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
            RegisteredCandidate registered = new RegisteredCandidate(database, sourceType, metadata);
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

    private void verifyMetadata(RegisteredCandidate registered, InspectedDatabase inspected) {
        DatabaseBackupMetadata metadata = registered.metadata();
        if (metadata == null) return;
        boolean valid = DatabaseBackupEngine.METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment())
                && metadata.schemaVersion() == inspected.schemaVersion()
                && metadata.schemaVersion() <= PlatformSchemaMigration.CURRENT_SCHEMA_VERSION
                && metadata.revision() == inspected.state().revision()
                && metadata.instanceId().equals(inspected.state().instanceId())
                && metadata.fileSize() == inspected.fileSize()
                && metadata.sha256().equalsIgnoreCase(inspected.sha256());
        if (!valid) invalid();
    }

    private boolean metadataMatchesEnvironment(DatabaseBackupMetadata metadata) {
        return metadata != null
                && DatabaseBackupEngine.METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment());
    }

    private ImportSummary requireImportSummary(Path path) {
        try {
            org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
                 var factory = connection.prepareStatement("""
                     SELECT username FROM operator_accounts
                      WHERE account_type='FACTORY_ADMIN' AND enabled=1 ORDER BY id
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

    private record RegisteredCandidate(Path path, String sourceType, DatabaseBackupMetadata metadata) {}
    private record ImportSummary(String factoryAdminUsername, long memberCount) {}
}
