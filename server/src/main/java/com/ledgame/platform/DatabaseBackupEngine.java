package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DataProtectionKeyManager dataKeys;
    private final ProtectedDataService protectedData;
    private final AvatarStorageService avatarStorage;
    private final DatabaseRecoveryKeyService recoveryKeys;

    @Autowired
    public DatabaseBackupEngine(
            SqliteOnlineBackup onlineBackup,
            DatabaseFileInspector inspector,
            ObjectMapper objectMapper,
            DatabaseBackupProperties properties,
            Clock clock,
            @Value("${ledgame.time-zone:Asia/Shanghai}") String timeZone,
            @Value("${spring.datasource.url}") String datasourceUrl,
            DataProtectionKeyManager dataKeys,
            ProtectedDataService protectedData,
            AvatarStorageService avatarStorage,
            DatabaseRecoveryKeyService recoveryKeys) {
        this.onlineBackup = onlineBackup;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.zoneId = ZoneId.of(timeZone);
        this.inMemoryDatabase = isInMemoryDatabase(datasourceUrl);
        this.sourceDatabase = databasePath(datasourceUrl);
        this.dataKeys = dataKeys;
        this.protectedData = protectedData;
        this.avatarStorage = avatarStorage;
        this.recoveryKeys = recoveryKeys;
    }

    /** Compatibility constructor retained for focused backup-engine tests. */
    DatabaseBackupEngine(
            SqliteOnlineBackup onlineBackup,
            DatabaseFileInspector inspector,
            ObjectMapper objectMapper,
            DatabaseBackupProperties properties,
            Clock clock,
            String timeZone,
            String datasourceUrl,
            DataProtectionKeyManager dataKeys,
            ProtectedDataService protectedData,
            AvatarStorageService avatarStorage) {
        this(onlineBackup, inspector, objectMapper, properties, clock, timeZone, datasourceUrl,
                dataKeys, protectedData, avatarStorage, null);
    }

    /** Compatibility constructor retained for focused backup-engine tests. */
    DatabaseBackupEngine(
            SqliteOnlineBackup onlineBackup,
            DatabaseFileInspector inspector,
            ObjectMapper objectMapper,
            DatabaseBackupProperties properties,
            Clock clock,
            String timeZone,
            String datasourceUrl,
            DataProtectionKeyManager dataKeys,
            ProtectedDataService protectedData) {
        this(onlineBackup, inspector, objectMapper, properties, clock, timeZone, datasourceUrl,
                dataKeys, protectedData, null, null);
    }

    DatabaseBackupEngine(
            SqliteOnlineBackup onlineBackup, DatabaseFileInspector inspector, ObjectMapper objectMapper,
            DatabaseBackupProperties properties, Clock clock, String timeZone, String datasourceUrl) {
        this.onlineBackup = onlineBackup;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.zoneId = ZoneId.of(timeZone);
        this.inMemoryDatabase = isInMemoryDatabase(datasourceUrl);
        this.sourceDatabase = databasePath(datasourceUrl);
        this.dataKeys = null;
        this.protectedData = null;
        this.avatarStorage = null;
        this.recoveryKeys = null;
    }

    public Path sourceDatabase() { return sourceDatabase; }
    public boolean inMemoryDatabase() { return inMemoryDatabase; }
    public String environment() { return properties.getEnvironment(); }

    /**
     * Test runs use a named temporary SQLite database.  Treat that name as an
     * explicit boundary so a test process can never publish into a production
     * backup root when an environment variable was omitted or misconfigured.
     */
    public boolean ephemeralDatabase() {
        String fileName = sourceDatabase.getFileName() == null
                ? "" : sourceDatabase.getFileName().toString();
        return inMemoryDatabase || fileName.startsWith("ledgame-sqlite-memory-");
    }

    public boolean acceptsMetadata(DatabaseBackupMetadata metadata) {
        boolean shapeValid = metadata != null
                && METADATA_FORMAT.equals(metadata.format())
                && properties.getEnvironment().equals(metadata.environment())
                && ProtectedDataService.ENCRYPTION_VERSION.equals(metadata.encryptionVersion())
                && metadata.keyId() != null && !metadata.keyId().isBlank();
        if (!shapeValid) return false;
        if (dataKeys == null) return true;
        try {
            // TEST runs have no DPAPI file, but the fixed test key still has a
            // stable identity. A metadata/key mismatch must not be accepted
            // merely because the envelope is intentionally absent.
            return metadata.keyId().equals(dataKeys.loadExisting().keyId());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean acceptsKeyEnvelope(Path envelopePath, DatabaseBackupMetadata metadata) {
        if (!acceptsMetadata(metadata)) return false;
        if (dataKeys == null || !dataKeys.requiresProtectedEnvelope()) return true;
        try {
            return metadata.keyId().equals(dataKeys.loadEnvelope(envelopePath).keyId());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public DatabaseBackupMetadata backup(Path root, String targetDiskIdentity) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try (BackupTargetLock ignored = BackupTargetLock.acquire(normalizedRoot)) {
            return backupLocked(normalizedRoot, targetDiskIdentity);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(BackupErrorCode.BACKUP_PUBLISH_FAILED.name(), exception);
        }
    }

    private DatabaseBackupMetadata backupLocked(Path normalizedRoot, String targetDiskIdentity) {
        Path stagingDirectory = normalizedRoot.resolve("staging");
        Path latestDirectory = normalizedRoot.resolve("latest");
        Path historyDirectory = normalizedRoot.resolve("history");
        Path candidate = stagingDirectory.resolve("platform-" + UUID.randomUUID() + ".db.tmp");
        Path candidateKey = stagingDirectory.resolve(candidate.getFileName() + ".data-key.dpapi");
        Path candidateAvatars = stagingDirectory.resolve("avatars-" + UUID.randomUUID());
        Path candidateAvatarManifest = stagingDirectory.resolve(candidateAvatars.getFileName() + ".json");
        Path candidateRecovery = stagingDirectory.resolve(candidate.getFileName() + ".factory-key-envelope.json");
        Path candidateMetadata = stagingDirectory.resolve(candidate.getFileName() + ".json");
        try {
            Files.createDirectories(stagingDirectory);
            Files.createDirectories(latestDirectory);
            Files.createDirectories(historyDirectory);
            cleanupStaging(stagingDirectory);
            onlineBackup.create(candidate);
            InspectedDatabase inspected = inspector.inspect(candidate);
            if (!inspected.valid()) throw new IllegalStateException(BackupErrorCode.BACKUP_INTEGRITY_FAILED.name());
            verifyProtectedDatabase(candidate);

            DataKeyMaterial storeKey = dataKeys == null ? null : dataKeys.loadExisting();
            String keyId = protectedData == null
                    ? (storeKey == null ? "test-key" : storeKey.keyId()) : protectedData.keyId();
            if (storeKey != null && !storeKey.keyId().equals(keyId)) {
                throw new IllegalStateException("DATA_PROTECTION_BACKUP_KEY_MISMATCH");
            }
            stageKeyEnvelope(candidateKey, storeKey);
            stageAvatars(candidateAvatars, candidateAvatarManifest);
            DatabaseRecoveryEnvelope recoveryEnvelope = null;
            if (recoveryKeys != null && recoveryKeys.enabled() && storeKey != null) {
                recoveryEnvelope = recoveryKeys.wrap(storeKey);
                Files.write(candidateRecovery, recoveryKeys.serialize(recoveryEnvelope));
            }
            Instant generatedAt = clock.instant();
            DatabaseStateSnapshot state = inspected.state();
            DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(
                    METADATA_FORMAT, properties.getEnvironment(), inspected.schemaVersion(),
                    state.instanceId(), state.revision(),
                    state.lastBusinessModifiedAt(), state.importedFromRevision(), state.importedAt(), generatedAt,
                    sourceDatabase.toString(), targetDiskIdentity, inspected.fileSize(), inspected.sha256(),
                    inspected.integrityResult(), ProtectedDataService.ENCRYPTION_VERSION, keyId,
                    recoveryEnvelope == null ? null : recoveryEnvelope.recoveryKeyId(),
                    recoveryEnvelope == null ? null : recoveryEnvelope.format(),
                    recoveryEnvelope == null ? null : inspector.sha256(candidateRecovery));
            writeJson(candidateMetadata, metadata);
            publishAvatars(candidateAvatars, candidateAvatarManifest, latestDirectory);
            publishPair(candidate, candidateMetadata,
                    Files.isRegularFile(candidateKey) ? candidateKey : null,
                    latestDirectory.resolve("platform.db"), latestDirectory.resolve("metadata.json"),
                    latestDirectory.resolve("data-key.dpapi"),
                    recoveryEnvelope == null ? null : candidateRecovery,
                    latestDirectory.resolve("factory-key-envelope.json"));
            createDailyHistory(latestDirectory, historyDirectory, metadata);
            cleanupHistory(historyDirectory);
            return metadata;
        } catch (Exception exception) {
            try { Files.deleteIfExists(candidate); } catch (IOException ignored) {}
            try { Files.deleteIfExists(candidateMetadata); } catch (IOException ignored) {}
            try { Files.deleteIfExists(candidateKey); } catch (IOException ignored) {}
            try { Files.deleteIfExists(candidateRecovery); } catch (IOException ignored) {}
            deleteTree(candidateAvatars);
            try { Files.deleteIfExists(candidateAvatarManifest); } catch (IOException ignored) {}
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
        copyTree(latestDirectory.resolve("avatars"), historyDirectory.resolve(prefix + "-avatars"));
        if (Files.isRegularFile(latestDirectory.resolve("avatar-manifest.json"))) {
            Files.copy(latestDirectory.resolve("avatar-manifest.json"), historyDirectory.resolve(prefix + "-avatar-manifest.json"), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path latestKey = latestDirectory.resolve("data-key.dpapi");
        if (Files.isRegularFile(latestKey)) {
            Files.copy(latestKey, historyDirectory.resolve(prefix + "-data-key.dpapi"),
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path latestRecovery = latestDirectory.resolve("factory-key-envelope.json");
        if (Files.isRegularFile(latestRecovery)) {
            Files.copy(latestRecovery, historyDirectory.resolve(prefix + "-factory-key-envelope.json"),
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
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
                Files.deleteIfExists(Path.of(database.toString().replace("-platform.db", "-data-key.dpapi")));
                Files.deleteIfExists(Path.of(database.toString().replace("-platform.db", "-factory-key-envelope.json")));
                deleteTree(Path.of(database.toString().replace("-platform.db", "-avatars")));
                Files.deleteIfExists(Path.of(database.toString().replace("-platform.db", "-avatar-manifest.json")));
            }
        }
    }

    private void stageKeyEnvelope(Path candidateKey, DataKeyMaterial storeKey) throws IOException {
        if (dataKeys == null || !dataKeys.requiresProtectedEnvelope()) return;
        byte[] envelope = dataKeys.envelopeBytes();
        if (envelope.length == 0) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_MISSING");
        }
        try {
            DataProtectionKeyManager.KeyEnvelope parsed = objectMapper.readValue(
                    envelope, DataProtectionKeyManager.KeyEnvelope.class);
            if (!DataProtectionKeyManager.ENVELOPE_FORMAT.equals(parsed.format())
                    || parsed.keyId() == null || parsed.keyId().isBlank()
                    || parsed.protectedKey() == null || parsed.protectedKey().isBlank()) {
                throw new IllegalStateException("DATA_PROTECTION_KEY_FORMAT_INVALID");
            }
            if (storeKey == null || !storeKey.keyId().equals(parsed.keyId())) {
                throw new IllegalStateException("DATA_PROTECTION_KEY_ID_MISMATCH");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_FORMAT_INVALID", exception);
        }
        Files.write(candidateKey, envelope, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void verifyProtectedDatabase(Path database) {
        if (protectedData == null) return;
        try {
            org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
                 var state = connection.prepareStatement(
                         "SELECT format_version, key_id, status FROM data_protection_state WHERE id=1");
                 var rows = state.executeQuery()) {
                if (!rows.next()
                        || rows.getInt("format_version") != DataProtectionMigration.FORMAT_VERSION
                        || !"COMPLETE".equals(rows.getString("status"))
                        || !protectedData.keyId().equals(rows.getString("key_id"))) {
                    throw new IllegalStateException("DATA_PROTECTION_BACKUP_STATE_INVALID");
                }
                verifyColumn(connection, "members", "phone");
                verifyColumn(connection, "members", "name");
                verifyColumn(connection, "members", "avatar_id");
                verifyColumn(connection, "members", "birthday");
                verifyColumn(connection, "members", "gender");
                verifyColumn(connection, "wristbands", "card_uid");
                verifyColumn(connection, "wristband_charge_records", "wristband_uid");
                verifyColumn(connection, "game_play_records", "wristband_uid");
                verifyColumn(connection, "game_play_records", "result_json");
                verifyColumn(connection, "operator_action_logs", "operator_username");
                verifyColumn(connection, "operator_action_logs", "operator_display_name");
                verifyColumn(connection, "operator_action_logs", "target_id");
                verifyColumn(connection, "operator_action_logs", "summary_json");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("DATA_PROTECTION_BACKUP_VERIFY_FAILED", exception);
        }
    }

    private void verifyColumn(java.sql.Connection connection, String table, String column) throws Exception {
        try (var plaintext = connection.createStatement();
             var rows = plaintext.executeQuery("SELECT COUNT(*) FROM " + table
                     + " WHERE " + column + " IS NOT NULL AND " + column + " NOT LIKE 'enc:v1:%'")) {
            if (rows.next() && rows.getLong(1) != 0) {
                throw new IllegalStateException("DATA_PROTECTION_BACKUP_PLAINTEXT_FOUND: " + table + "." + column);
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL LIMIT 50");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String value = rows.getString(1);
                if (!protectedData.isEncrypted(value)) {
                    throw new IllegalStateException("DATA_PROTECTION_BACKUP_PLAINTEXT_FOUND: " + table + "." + column);
                }
                protectedData.decryptField(table, column, value);
            }
        }
    }

    private Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException exception) { return Instant.EPOCH; }
    }

    private void cleanupStaging(Path stagingDirectory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
            for (Path item : stream) deleteTree(item);
        }
    }

    private void stageAvatars(Path targetDirectory, Path manifestPath) throws IOException {
        Files.createDirectories(targetDirectory);
        List<AvatarBackupManifest.Entry> entries = new ArrayList<>();
        if (avatarStorage != null) {
            for (Path source : avatarStorage.storedFiles()) {
                Path target = targetDirectory.resolve(source.getFileName().toString());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                entries.add(new AvatarBackupManifest.Entry(target.getFileName().toString(), Files.size(target), inspector.sha256(target)));
            }
        }
        writeJson(manifestPath, new AvatarBackupManifest(AvatarBackupManifest.FORMAT, entries));
    }

    private void publishAvatars(Path stagedDirectory, Path stagedManifest, Path latestDirectory) throws IOException {
        Path target = latestDirectory.resolve("avatars");
        Path previous = latestDirectory.resolve("avatars.previous");
        deleteTree(previous);
        if (Files.exists(target)) move(target, previous, false);
        try {
            move(stagedDirectory, target, true);
            move(stagedManifest, latestDirectory.resolve("avatar-manifest.json"), true);
            deleteTree(previous);
        } catch (IOException exception) {
            deleteTree(target);
            if (Files.exists(previous)) move(previous, target, true);
            throw exception;
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        });
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * Prevents two application processes from publishing different generations
     * into the same target directory at the same time. Without this lock, one
     * process could publish a database while another publishes its key envelope,
     * leaving a syntactically valid but unusable mixed backup.
     */
    private static final class BackupTargetLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private BackupTargetLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static BackupTargetLock acquire(Path root) {
            try {
                Files.createDirectories(root);
                FileChannel channel = FileChannel.open(root.resolve(".backup.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        channel.close();
                        throw new IllegalStateException("BACKUP_TARGET_BUSY");
                    }
                    return new BackupTargetLock(channel, lock);
                } catch (OverlappingFileLockException exception) {
                    channel.close();
                    throw new IllegalStateException("BACKUP_TARGET_BUSY", exception);
                } catch (RuntimeException | IOException exception) {
                    try { channel.close(); } catch (IOException ignored) { }
                    if (exception instanceof IllegalStateException stateException) throw stateException;
                    throw new IllegalStateException("BACKUP_TARGET_LOCK_FAILED", exception);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("BACKUP_TARGET_LOCK_FAILED", exception);
            }
        }

        @Override public void close() throws IOException {
            try { lock.release(); }
            finally { channel.close(); }
        }
    }

    private void publishPair(Path candidateDatabase, Path candidateMetadata, Path candidateKey,
            Path latestDatabase, Path latestMetadata, Path latestKey,
            Path candidateRecovery, Path latestRecovery) throws IOException {
        Path previousDatabase = latestDatabase.resolveSibling("platform.db.previous");
        Path previousMetadata = latestMetadata.resolveSibling("metadata.json.previous");
        Path previousKey = latestKey.resolveSibling("data-key.dpapi.previous");
        Path previousRecovery = latestRecovery == null ? null : latestRecovery.resolveSibling("factory-key-envelope.json.previous");
        Files.deleteIfExists(previousDatabase);
        Files.deleteIfExists(previousMetadata);
        Files.deleteIfExists(previousKey);
        if (previousRecovery != null) Files.deleteIfExists(previousRecovery);
        if (Files.exists(latestDatabase)) move(latestDatabase, previousDatabase, false);
        if (Files.exists(latestMetadata)) move(latestMetadata, previousMetadata, false);
        if (Files.exists(latestKey)) move(latestKey, previousKey, false);
        if (latestRecovery != null && Files.exists(latestRecovery)) move(latestRecovery, previousRecovery, false);
        try {
            move(candidateDatabase, latestDatabase, true);
            move(candidateMetadata, latestMetadata, true);
            if (candidateKey != null) move(candidateKey, latestKey, true);
            if (candidateRecovery != null) move(candidateRecovery, latestRecovery, true);
            Files.deleteIfExists(previousDatabase);
            Files.deleteIfExists(previousMetadata);
            Files.deleteIfExists(previousKey);
            if (previousRecovery != null) Files.deleteIfExists(previousRecovery);
        } catch (IOException exception) {
            Files.deleteIfExists(latestDatabase);
            Files.deleteIfExists(latestMetadata);
            Files.deleteIfExists(latestKey);
            if (latestRecovery != null) Files.deleteIfExists(latestRecovery);
            if (Files.exists(previousDatabase)) move(previousDatabase, latestDatabase, true);
            if (Files.exists(previousMetadata)) move(previousMetadata, latestMetadata, true);
            if (Files.exists(previousKey)) move(previousKey, latestKey, true);
            if (previousRecovery != null && Files.exists(previousRecovery)) move(previousRecovery, latestRecovery, true);
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
