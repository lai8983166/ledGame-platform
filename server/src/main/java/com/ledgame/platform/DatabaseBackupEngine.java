package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DatabaseBackupEngine {
    public static final String METADATA_FORMAT = "ledgame-platform-backup-v2";
    private static final DateTimeFormatter HISTORY_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final SqliteOnlineBackup onlineBackup;
    private final DatabaseFileInspector inspector;
    private final ObjectMapper objectMapper;
    private final DatabaseBackupProperties properties;
    private final Clock clock;
    private final ZoneId zoneId;
    private final Path sourceDatabase;
    private final boolean inMemoryDatabase;

    public DatabaseBackupEngine(
            SqliteOnlineBackup onlineBackup,
            DatabaseFileInspector inspector,
            ObjectMapper objectMapper,
            DatabaseBackupProperties properties,
            Clock clock,
            @Value("${ledgame.time-zone:Asia/Shanghai}") String timeZone,
            @Value("${spring.datasource.url}") String datasourceUrl) {
        this.onlineBackup = onlineBackup;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.zoneId = ZoneId.of(timeZone);
        this.inMemoryDatabase = isInMemoryDatabase(datasourceUrl);
        this.sourceDatabase = databasePath(datasourceUrl);
    }

    public Path sourceDatabase() { return sourceDatabase; }
    public boolean inMemoryDatabase() { return inMemoryDatabase; }
    public String environment() { return properties.getEnvironment(); }

    public boolean acceptsMetadata(DatabaseBackupMetadata metadata) {
        return metadata != null
                && METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment());
    }

    public DatabaseBackupMetadata backup(Path root, String targetDiskIdentity) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path stagingDirectory = normalizedRoot.resolve("staging");
        Path latestDirectory = normalizedRoot.resolve("latest");
        Path historyDirectory = normalizedRoot.resolve("history");
        Path candidate = stagingDirectory.resolve("platform-" + UUID.randomUUID() + ".db.tmp");
        try {
            Files.createDirectories(stagingDirectory);
            Files.createDirectories(latestDirectory);
            Files.createDirectories(historyDirectory);
            cleanupStaging(stagingDirectory);
            onlineBackup.create(candidate);
            InspectedDatabase inspected = inspector.inspect(candidate);
            if (!inspected.valid()) throw new IllegalStateException(BackupErrorCode.BACKUP_INTEGRITY_FAILED.name());
            Instant generatedAt = clock.instant();
            DatabaseStateSnapshot state = inspected.state();
            DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(
                    METADATA_FORMAT, properties.getEnvironment(), inspected.schemaVersion(),
                    state.instanceId(), state.revision(),
                    state.lastBusinessModifiedAt(), state.importedFromRevision(), state.importedAt(), generatedAt,
                    sourceDatabase.toString(), targetDiskIdentity, inspected.fileSize(), inspected.sha256(),
                    inspected.integrityResult());
            Path candidateMetadata = stagingDirectory.resolve(candidate.getFileName() + ".json");
            writeJson(candidateMetadata, metadata);
            publishPair(candidate, candidateMetadata,
                    latestDirectory.resolve("platform.db"), latestDirectory.resolve("metadata.json"));
            createDailyHistory(latestDirectory, historyDirectory, metadata);
            cleanupHistory(historyDirectory);
            return metadata;
        } catch (Exception exception) {
            try { Files.deleteIfExists(candidate); } catch (IOException ignored) {}
            try { Files.deleteIfExists(stagingDirectory.resolve(candidate.getFileName() + ".json")); } catch (IOException ignored) {}
            if (exception instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException(BackupErrorCode.BACKUP_PUBLISH_FAILED.name(), exception);
        }
    }

    public InspectedDatabase inspectLatest(Path root) {
        return inspector.inspect(root.resolve("latest").resolve("platform.db"));
    }

    public DatabaseBackupMetadata readLatestMetadata(Path root) {
        try {
            return objectMapper.readValue(root.resolve("latest").resolve("metadata.json").toFile(),
                    DatabaseBackupMetadata.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read backup metadata", exception);
        }
    }

    private void createDailyHistory(Path latestDirectory, Path historyDirectory, DatabaseBackupMetadata metadata)
            throws IOException {
        LocalDate today = LocalDate.ofInstant(metadata.generatedAt(), zoneId);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDirectory,
                today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-*-platform.db")) {
            if (stream.iterator().hasNext()) return;
        }
        String prefix = HISTORY_NAME.format(metadata.generatedAt().atZone(zoneId));
        Path historyDatabase = historyDirectory.resolve(prefix + "-platform.db");
        Path historyMetadata = historyDirectory.resolve(prefix + "-platform.json");
        Files.copy(latestDirectory.resolve("platform.db"), historyDatabase, StandardCopyOption.COPY_ATTRIBUTES);
        Files.copy(latestDirectory.resolve("metadata.json"), historyMetadata, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void cleanupHistory(Path historyDirectory) throws IOException {
        Instant cutoff = clock.instant().minusSeconds(Math.max(properties.getRetentionDays(), 1) * 86400L);
        List<Path> databases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDirectory, "*-platform.db")) {
            stream.forEach(databases::add);
        }
        databases.sort(Comparator.comparing(this::modified).reversed());
        for (int index = 1; index < databases.size(); index++) {
            Path database = databases.get(index);
            if (modified(database).isBefore(cutoff)) {
                Files.deleteIfExists(database);
                Files.deleteIfExists(Path.of(database.toString().replace("-platform.db", "-platform.json")));
            }
        }
    }

    private Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException exception) { return Instant.EPOCH; }
    }

    private void cleanupStaging(Path stagingDirectory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
            for (Path item : stream) Files.deleteIfExists(item);
        }
    }

    private void publishPair(Path candidateDatabase, Path candidateMetadata, Path latestDatabase, Path latestMetadata)
            throws IOException {
        Path previousDatabase = latestDatabase.resolveSibling("platform.db.previous");
        Path previousMetadata = latestMetadata.resolveSibling("metadata.json.previous");
        Files.deleteIfExists(previousDatabase);
        Files.deleteIfExists(previousMetadata);
        if (Files.exists(latestDatabase)) move(latestDatabase, previousDatabase, false);
        if (Files.exists(latestMetadata)) move(latestMetadata, previousMetadata, false);
        try {
            move(candidateDatabase, latestDatabase, true);
            move(candidateMetadata, latestMetadata, true);
            Files.deleteIfExists(previousDatabase);
            Files.deleteIfExists(previousMetadata);
        } catch (IOException exception) {
            Files.deleteIfExists(latestDatabase);
            Files.deleteIfExists(latestMetadata);
            if (Files.exists(previousDatabase)) move(previousDatabase, latestDatabase, true);
            if (Files.exists(previousMetadata)) move(previousMetadata, latestMetadata, true);
            throw exception;
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".writing");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        move(temporary, path, true);
    }

    static Path databasePath(String datasourceUrl) {
        String prefix = "jdbc:sqlite:";
        if (datasourceUrl == null || !datasourceUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("Only jdbc:sqlite datasource URLs are supported");
        }
        String sqliteLocation = datasourceUrl.substring(prefix.length());
        if (":memory:".equals(sqliteLocation)) {
            return Path.of(System.getProperty("java.io.tmpdir"), "ledgame-sqlite-memory.db")
                    .toAbsolutePath().normalize();
        }
        if (sqliteLocation.startsWith("file:")) {
            String uriLocation = sqliteLocation.substring("file:".length());
            int queryStart = uriLocation.indexOf('?');
            String query = queryStart >= 0 ? uriLocation.substring(queryStart + 1) : "";
            if (query.contains("mode=memory")) {
                // In-memory databases have no physical file to back up. The coordinator short-circuits
                // when backup is disabled (as it is in automated tests); this stable placeholder merely
                // keeps Spring bean creation independent of Windows path parsing rules.
                String name = queryStart >= 0 ? uriLocation.substring(0, queryStart) : uriLocation;
                return Path.of(System.getProperty("java.io.tmpdir"),
                        "ledgame-sqlite-memory-" + Integer.toUnsignedString(name.hashCode()) + ".db")
                        .toAbsolutePath().normalize();
            }
            sqliteLocation = queryStart >= 0 ? uriLocation.substring(0, queryStart) : uriLocation;
        }
        return Path.of(sqliteLocation).toAbsolutePath().normalize();
    }

    static boolean isInMemoryDatabase(String datasourceUrl) {
        if (datasourceUrl == null) return false;
        return datasourceUrl.equals("jdbc:sqlite::memory:")
                || (datasourceUrl.startsWith("jdbc:sqlite:file:") && datasourceUrl.contains("mode=memory"));
    }
}
