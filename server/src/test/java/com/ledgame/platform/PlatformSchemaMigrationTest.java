package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PlatformSchemaMigrationTest {
    @Test
    void currentSchemaTracksEveryProtectedBusinessTableWithTransactionalRevisionTriggers() throws Exception {
        Path database = Files.createTempFile("platform-backup-revision-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));

            List<String> protectedTables = List.of(
                    "members", "wristbands", "wristband_charge_records", "wristband_bindings",
                    "game_play_records", "room_settings", "store_feature_settings",
                    "operator_accounts", "operator_action_logs");
            for (String table : protectedTables) {
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sqlite_master
                     WHERE type='trigger' AND tbl_name=? AND name LIKE 'backup_revision_%'
                    """, Integer.class, table)).as(table).isEqualTo(3);
            }

            long before = jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class);
            jdbc.update("""
                INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
                VALUES ('13800138000', '版本测试', 'ACTIVE', 'now', 'now', 'test')
                """);
            assertThat(jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class))
                    .isGreaterThan(before);

            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("""
                    INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
                    VALUES ('13900139000', '回滚测试', 'ACTIVE', 'now', 'now', 'test')
                    """)) {
                    statement.executeUpdate();
                }
                connection.rollback();
            }
            assertThat(jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class))
                    .isEqualTo(before + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(1);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void bootstrapsOneConfigurableFactoryAdminAndNeverOverwritesExistingAccounts() throws Exception {
        Path database = Files.createTempFile("platform-operator-bootstrap-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }

            OperatorAccountProperties properties = new OperatorAccountProperties();
            properties.getFactory().setUsername("factory-test");
            properties.getFactory().setPassword("test-password");
            properties.getFactory().setDisplayName("测试出厂管理员");
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
            OperatorAccountBootstrap bootstrap = new OperatorAccountBootstrap(
                    jdbc, encoder, properties,
                    Clock.fixed(Instant.parse("2026-08-30T02:00:00Z"), ZoneOffset.UTC));

            bootstrap.run(new DefaultApplicationArguments(new String[0]));
            String originalHash = jdbc.queryForObject(
                    "SELECT password_hash FROM operator_accounts WHERE username='factory-test'", String.class);
            assertThat(originalHash).startsWith("$2").doesNotContain("test-password");
            assertThat(encoder.matches("test-password", originalHash)).isTrue();

            properties.getFactory().setPassword("replacement-must-not-apply");
            bootstrap.run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_accounts", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM operator_accounts"))
                    .containsEntry("username", "factory-test")
                    .containsEntry("display_name", "测试出厂管理员")
                    .containsEntry("account_type", "FACTORY_ADMIN")
                    .containsEntry("enabled", 1);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM operator_accounts", String.class))
                    .isEqualTo(originalHash);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void currentSchemaDefinesOperatorAccountsAndActionLogsWithCaseInsensitiveUsernames() throws Exception {
        Path database = Files.createTempFile("platform-operator-schema-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='operator_accounts'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='operator_action_logs'",
                    Integer.class)).isEqualTo(1);

            jdbc.update("""
                INSERT INTO operator_accounts(
                    username, display_name, password_hash, account_type,
                    enabled, created_at, updated_at)
                VALUES ('StoreUser', '门店操作员', 'hash-a', 'OPERATOR', 1, 'now', 'now')
                """);
            org.junit.jupiter.api.Assertions.assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO operator_accounts(
                    username, display_name, password_hash, account_type,
                    enabled, created_at, updated_at)
                VALUES ('storeuser', '重名操作员', 'hash-b', 'OPERATOR', 1, 'now', 'now')
                """));
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void addsMemberDeletionMarkerToLegacyDatabaseIdempotently() throws Exception {
        Path database = Files.createTempFile("platform-member-delete-legacy-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE members(id INTEGER PRIMARY KEY, phone TEXT NOT NULL, status TEXT NOT NULL)");

            PlatformSchemaMigration migration = new PlatformSchemaMigration(jdbc);
            migration.run(new DefaultApplicationArguments(new String[0]));
            migration.run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForList("PRAGMA table_info(members)").stream()
                    .map(column -> String.valueOf(column.get("name"))))
                    .contains("deleted_at");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pragma_table_info('members') WHERE name='deleted_at'", Integer.class))
                    .isEqualTo(1);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void addsScoringPolicyToAnExistingLegacySqliteDatabase() throws Exception {
        Path database = Files.createTempFile("platform-legacy-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE game_play_records(id INTEGER PRIMARY KEY, points_awarded INTEGER NOT NULL DEFAULT 0)");

            new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));
            new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForList("PRAGMA table_info(game_play_records)").stream()
                    .map(column -> String.valueOf(column.get("name"))))
                    .contains("scoring_policy");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void migratesLegacySinglePlayerIndexForMultipleParticipantsIdempotently() throws Exception {
        Path database = Files.createTempFile("platform-multiplayer-legacy-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                CREATE TABLE game_play_records(
                    id INTEGER PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    external_session_id TEXT NOT NULL,
                    binding_id INTEGER NOT NULL,
                    points_awarded INTEGER NOT NULL DEFAULT 0,
                    scoring_policy TEXT,
                    result_json TEXT)
                """);
            jdbc.execute("""
                CREATE UNIQUE INDEX ux_game_play_external_session
                    ON game_play_records(device_id, external_session_id)
                """);
            jdbc.update("""
                INSERT INTO game_play_records(
                    id, device_id, external_session_id, binding_id,
                    points_awarded, scoring_policy, result_json)
                VALUES (7, 'legacy-device', 'legacy-session', 17, 23, 'raw-score-v1', '{"legacy":true}')
                """);

            PlatformSchemaMigration migration = new PlatformSchemaMigration(jdbc);
            migration.run(new DefaultApplicationArguments(new String[0]));
            migration.run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForList("PRAGMA table_info(game_play_records)").stream()
                    .map(column -> String.valueOf(column.get("name"))))
                    .contains("participant_index");
            assertThat(jdbc.queryForObject(
                    "SELECT participant_index FROM game_play_records WHERE id=7", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForMap("""
                    SELECT points_awarded AS points, scoring_policy AS policy, result_json AS result
                      FROM game_play_records WHERE id=7
                    """))
                    .containsEntry("points", 23)
                    .containsEntry("policy", "raw-score-v1")
                    .containsEntry("result", "{\"legacy\":true}");
            assertThat(jdbc.queryForList("PRAGMA index_list(game_play_records)").stream()
                    .map(index -> String.valueOf(index.get("name"))))
                    .contains("ux_game_play_session_binding", "ux_game_play_session_participant")
                    .doesNotContain("ux_game_play_external_session");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void currentSchemaInitializationCanRunBeforeMigratingALegacyDatabase() throws Exception {
        Path database = Files.createTempFile("platform-schema-before-migration-", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database.toAbsolutePath());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                CREATE TABLE game_play_records(
                    id INTEGER PRIMARY KEY,
                    member_id INTEGER NOT NULL,
                    binding_id INTEGER NOT NULL,
                    wristband_uid TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    room_id TEXT,
                    external_session_id TEXT NOT NULL,
                    game_id TEXT NOT NULL,
                    game_name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    ended_at TEXT,
                    success INTEGER,
                    termination_reason TEXT,
                    raw_score INTEGER,
                    points_awarded INTEGER NOT NULL DEFAULT 0,
                    scoring_policy TEXT,
                    result_json TEXT)
                """);
            jdbc.execute("""
                CREATE UNIQUE INDEX ux_game_play_external_session
                    ON game_play_records(device_id, external_session_id)
                """);

            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForList("PRAGMA table_info(game_play_records)").stream()
                    .map(column -> String.valueOf(column.get("name"))))
                    .contains("participant_index");
            assertThat(jdbc.queryForList("PRAGMA index_list(game_play_records)").stream()
                    .map(index -> String.valueOf(index.get("name"))))
                    .contains("ux_game_play_session_binding", "ux_game_play_session_participant")
                    .doesNotContain("ux_game_play_external_session");
        } finally {
            Files.deleteIfExists(database);
        }
    }
}
