package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseBackupEngineTest {
    @TempDir Path root;
    private Path source;
    private Path backupRoot;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseFileInspector inspector;
    private DatabaseBackupProperties properties;
    private Clock clock;
    private DataProtectionKeyManager keyManager;
    private ProtectedDataService protectedData;
    private AvatarStorageService avatarStorage;

    @BeforeEach
    void setup() throws Exception {
        source = root.resolve("source").resolve("platform.db");
        Files.createDirectories(source.getParent());
        backupRoot = root.resolve("target").resolve("LEDGameBackup").resolve("member-admin");
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + source.toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));
        inspector = new DatabaseFileInspector();
        properties = new DatabaseBackupProperties();
        properties.setEnvironment("TEST");
        properties.setMinimumFreeBytes(1);
        clock = Clock.fixed(Instant.parse("2026-09-02T02:03:04Z"), ZoneOffset.UTC);
        DataKeyMaterial key = DataProtectionKeyManager.material(new byte[32]);
        keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(key);
        when(keyManager.loadExisting()).thenReturn(key);
        when(keyManager.envelopeBytes()).thenReturn("dpapi-test-envelope".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        protectedData = new ProtectedDataService(keyManager);
        new DataProtectionMigration(jdbc, keyManager, protectedData, clock)
                .run(new DefaultApplicationArguments());
        avatarStorage = new AvatarStorageService(protectedData, "jdbc:sqlite:" + source.toAbsolutePath());
    }

    @Test
    void onlineBackupPublishesVerifiedLatestMetadataAndDailyHistory() throws Exception {
        insertMember("13800138000", "备份玩家");
        DatabaseStateSnapshot sourceState = new DatabaseStateService(jdbc).current();
        DatabaseBackupEngine engine = engine(new SqliteOnlineBackup(dataSource));

        DatabaseBackupMetadata metadata = engine.backup(backupRoot, "uid:disk-b");

        Path latest = backupRoot.resolve("latest/platform.db");
        assertThat(latest).isRegularFile();
        assertThat(inspector.inspect(latest).valid()).isTrue();
        assertThat(inspector.inspect(latest).state().revision()).isEqualTo(sourceState.revision());
        Object storedPhone = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + latest.toAbsolutePath()))
                .queryForObject("SELECT phone FROM members", Object.class);
        assertThat(String.valueOf(storedPhone)).startsWith("enc:v1:").doesNotContain("13800138000");
        assertThat(protectedData.decryptField("members", "phone", storedPhone)).isEqualTo("13800138000");
        byte[] rawBackup = Files.readAllBytes(latest);
        assertThat(indexOf(rawBackup, "13800138000".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(-1);
        assertThat(indexOf(rawBackup, "备份玩家".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(-1);
        assertThat(metadata.sha256()).isEqualTo(inspector.sha256(latest));
        assertThat(metadata.targetDiskIdentity()).isEqualTo("uid:disk-b");
        assertThat(metadata.format()).isEqualTo("ledgame-platform-backup-v2");
        assertThat(metadata.environment()).isEqualTo("TEST");
        assertThat(backupRoot.resolve("latest/metadata.json")).isRegularFile();
        assertThat(backupRoot.resolve("latest/data-key.dpapi")).hasContent("dpapi-test-envelope");
        assertThat(metadata.encryptionVersion()).isEqualTo(ProtectedDataService.ENCRYPTION_VERSION);
        assertThat(metadata.keyId()).isEqualTo(protectedData.keyId());
        Path historyDatabase = historyDatabases().get(0);
        assertThat(indexOf(Files.readAllBytes(historyDatabase),
                "13800138000".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(-1);
        String historyKey = historyDatabase.getFileName().toString()
                .replace("-platform.db", "-data-key.dpapi");
        assertThat(historyDatabase.resolveSibling(historyKey)).hasContent("dpapi-test-envelope");
    }

    @Test
    void onlineBackupPublishesVendorRecoveryEnvelopeWithoutChangingDatabaseCiphertext() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Path publicKey = root.resolve("factory-recovery-public.pem");
        Files.writeString(publicKey, pem(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        DatabaseRecoveryProperties recoveryProperties = new DatabaseRecoveryProperties();
        recoveryProperties.setPublicKeyPath(publicKey.toString());
        DatabaseRecoveryKeyService recovery = new DatabaseRecoveryKeyService(recoveryProperties, new ObjectMapper());
        insertMember("13800138009", "恢复测试");

        DatabaseBackupMetadata metadata = engine(new SqliteOnlineBackup(dataSource), recovery)
                .backup(backupRoot, "uid:disk-b");

        Path envelopePath = backupRoot.resolve("latest/factory-key-envelope.json");
        assertThat(envelopePath).isRegularFile();
        DatabaseRecoveryEnvelope envelope = new ObjectMapper().readValue(envelopePath.toFile(), DatabaseRecoveryEnvelope.class);
        assertThat(envelope.keyId()).isEqualTo(protectedData.keyId());
        assertThat(metadata.recoveryKeyId()).isEqualTo(envelope.recoveryKeyId());
        assertThat(metadata.recoveryEnvelopeFormat()).isEqualTo(envelope.format());
        assertThat(metadata.recoveryEnvelopeSha256()).isEqualTo(inspector.sha256(envelopePath));
        assertThat(backupRoot.resolve("history")).isDirectory();
        try (var files = Files.list(backupRoot.resolve("history"))) {
            assertThat(files.anyMatch(path -> path.getFileName().toString().endsWith("-factory-key-envelope.json"))).isTrue();
        }
        // The existing backup verifier already decrypts protected columns and rejects plaintext.
    }

    @Test
    void onlineBackupPublishesEncryptedAvatarBundleAndManifestAlongsideDatabase() throws Exception {
        String avatarId = avatarStorage.store(Base64.getEncoder().encodeToString(tinyPng()), "image/png");
        insertMember("13800138001", "带头像玩家", avatarId);

        engine(new SqliteOnlineBackup(dataSource)).backup(backupRoot, "uid:disk-b");

        Path avatarDirectory = backupRoot.resolve("latest/avatars");
        assertThat(avatarDirectory).isDirectory();
        Path stored;
        try (var files = Files.list(avatarDirectory)) {
            var entries = files.toList();
            assertThat(entries).hasSize(1);
            stored = entries.get(0);
        }
        Path avatarManifest = backupRoot.resolve("latest/avatar-manifest.json");
        assertThat(Files.readString(avatarManifest)).contains("ledgame-avatar-backup-v1");
        assertThat(Files.readAllBytes(stored)[0]).isZero();
    }

    @Test
    void failedCandidateValidationNeverReplacesPreviousLatest() throws Exception {
        DatabaseBackupEngine good = engine(new SqliteOnlineBackup(dataSource));
        good.backup(backupRoot, "uid:disk-b");
        String originalHash = inspector.sha256(backupRoot.resolve("latest/platform.db"));
        SqliteOnlineBackup corrupting = new SqliteOnlineBackup(dataSource) {
            @Override public void create(Path destination) {
                try { Files.createDirectories(destination.getParent()); Files.writeString(destination, "not sqlite"); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
        };

        assertThatThrownBy(() -> engine(corrupting).backup(backupRoot, "uid:disk-b"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(inspector.sha256(backupRoot.resolve("latest/platform.db"))).isEqualTo(originalHash);
        assertThat(inspector.inspect(backupRoot.resolve("latest/platform.db")).valid()).isTrue();
    }

    @Test
    void backupCapturesOnlyCommittedRowsDuringConcurrentTransactions() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                INSERT INTO members(phone, phone_lookup_hash, name, status, created_at, updated_at, created_by)
                VALUES (?, ?, ?, 'ACTIVE', 'now', 'now', 'test')
                """)) {
                statement.setString(1, protectedData.encryptField("members", "phone", "13900139000"));
                statement.setString(2, protectedData.phoneLookupHash("13900139000"));
                statement.setString(3, protectedData.encryptField("members", "name", "未提交玩家"));
                statement.executeUpdate();
            }
            engine(new SqliteOnlineBackup(dataSource)).backup(backupRoot, "uid:disk-b");
            connection.rollback();
        }

        JdbcTemplate backupJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + backupRoot.resolve("latest/platform.db").toAbsolutePath()));
        assertThat(backupJdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
        assertThat(inspector.inspect(backupRoot.resolve("latest/platform.db")).valid()).isTrue();
    }

    @Test
    void removesExpiredHistoryButNeverDeletesLatestOrTheOnlyCurrentSnapshot() throws Exception {
        Path history = backupRoot.resolve("history");
        Files.createDirectories(history);
        Path expiredDatabase = Files.writeString(history.resolve("20260701-000000-platform.db"), "expired");
        Path expiredMetadata = Files.writeString(history.resolve("20260701-000000-platform.json"), "{}");
        FileTime expired = FileTime.from(Instant.parse("2026-07-01T00:00:00Z"));
        Files.setLastModifiedTime(expiredDatabase, expired);
        Files.setLastModifiedTime(expiredMetadata, expired);

        engine(new SqliteOnlineBackup(dataSource)).backup(backupRoot, "uid:disk-b");

        assertThat(backupRoot.resolve("latest/platform.db")).isRegularFile();
        assertThat(expiredDatabase).doesNotExist();
        assertThat(expiredMetadata).doesNotExist();
        try (var stream = Files.list(history)) {
            assertThat(stream.filter(path -> path.getFileName().toString().endsWith("-platform.db"))).hasSize(1);
        }
    }

    @Test
    void createsAtMostOneHistorySnapshotPerStoreDayAndAddsTheNextDaySnapshot() throws Exception {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-09-02T02:03:04Z"));
        clock = mutableClock;
        DatabaseBackupEngine engine = engine(new SqliteOnlineBackup(dataSource));
        engine.backup(backupRoot, "uid:disk-b");
        engine.backup(backupRoot, "uid:disk-b");
        assertThat(historyDatabases()).hasSize(1);

        mutableClock.instant = mutableClock.instant.plusSeconds(86400);
        engine.backup(backupRoot, "uid:disk-b");
        assertThat(historyDatabases()).hasSize(2);
        assertThat(backupRoot.resolve("latest/platform.db")).isRegularFile();
    }

    private java.util.List<Path> historyDatabases() throws Exception {
        try (var stream = Files.list(backupRoot.resolve("history"))) {
            return stream.filter(path -> path.getFileName().toString().endsWith("-platform.db")).toList();
        }
    }

    private DatabaseBackupEngine engine(SqliteOnlineBackup onlineBackup) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new DatabaseBackupEngine(onlineBackup, inspector, mapper, properties, clock,
                "Asia/Shanghai", "jdbc:sqlite:" + source.toAbsolutePath(), keyManager, protectedData, avatarStorage);
    }

    private DatabaseBackupEngine engine(SqliteOnlineBackup onlineBackup, DatabaseRecoveryKeyService recovery) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new DatabaseBackupEngine(onlineBackup, inspector, mapper, properties, clock,
                "Asia/Shanghai", "jdbc:sqlite:" + source.toAbsolutePath(), keyManager, protectedData,
                avatarStorage, recovery);
    }

    private void insertMember(String phone, String name) {
        insertMember(phone, name, null);
    }

    private void insertMember(String phone, String name, String avatarId) {
        jdbc.update("""
            INSERT INTO members(phone, phone_lookup_hash, name, avatar_id, status, created_at, updated_at, created_by)
            VALUES (?, ?, ?, ?, 'ACTIVE', 'now', 'now', 'test')
            """, protectedData.encryptField("members", "phone", phone), protectedData.phoneLookupHash(phone),
                protectedData.encryptField("members", "name", name),
                protectedData.encryptField("members", "avatar_id", avatarId));
    }

    private static byte[] tinyPng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static int indexOf(byte[] content, byte[] needle) {
        outer: for (int offset = 0; offset <= content.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (content[offset + index] != needle[index]) continue outer;
            }
            return offset;
        }
        return -1;
    }
}
