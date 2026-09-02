package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.nio.file.attribute.FileTime;
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
    }

    @Test
    void onlineBackupPublishesVerifiedLatestMetadataAndDailyHistory() throws Exception {
        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800138000', '备份玩家', 'ACTIVE', 'now', 'now', 'test')
            """);
        DatabaseStateSnapshot sourceState = new DatabaseStateService(jdbc).current();
        DatabaseBackupEngine engine = engine(new SqliteOnlineBackup(dataSource));

        DatabaseBackupMetadata metadata = engine.backup(backupRoot, "uid:disk-b");

        Path latest = backupRoot.resolve("latest/platform.db");
        assertThat(latest).isRegularFile();
        assertThat(inspector.inspect(latest).valid()).isTrue();
        assertThat(inspector.inspect(latest).state().revision()).isEqualTo(sourceState.revision());
        assertThat(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + latest.toAbsolutePath()))
                .queryForObject("SELECT COUNT(*) FROM members WHERE phone='13800138000'", Integer.class)).isEqualTo(1);
        assertThat(metadata.sha256()).isEqualTo(inspector.sha256(latest));
        assertThat(metadata.targetDiskIdentity()).isEqualTo("uid:disk-b");
        assertThat(metadata.format()).isEqualTo("ledgame-platform-backup-v2");
        assertThat(metadata.environment()).isEqualTo("TEST");
        assertThat(backupRoot.resolve("latest/metadata.json")).isRegularFile();
        try (var stream = Files.list(backupRoot.resolve("history"))) {
            assertThat(stream.filter(path -> path.getFileName().toString().endsWith("-platform.db"))).hasSize(1);
        }
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
                INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
                VALUES ('13900139000', '未提交玩家', 'ACTIVE', 'now', 'now', 'test')
                """)) {
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
                "Asia/Shanghai", "jdbc:sqlite:" + source.toAbsolutePath());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
