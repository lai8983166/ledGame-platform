package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DataProtectionMigrationTest {
    @TempDir java.nio.file.Path root;

    @Test
    void migratesRepresentativePlaintextWithoutChangingRelationshipsOrBusinessValues() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("migration.db").toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO operator_accounts(id, username, display_name, password_hash, account_type, enabled,
                                          created_at, updated_at)
            VALUES (1, 'admin', '出厂管理员', '$2a$hash-kept', 'FACTORY_ADMIN', 1, 'now', 'now')
            """);
        jdbc.update("""
            INSERT INTO members(id, phone, name, avatar_id, birthday, gender, status, created_at, updated_at, created_by)
            VALUES (7, '13800138000', '测试会员甲', 'nova', '2000-01-02', 'female', 'ACTIVE', 'now', 'now', 'test')
            """);
        jdbc.update("""
            INSERT INTO wristbands(id, card_uid, status, duration_minutes, charged_at, created_at, updated_at)
            VALUES (8, '2283055618', 'ACTIVE', 60, 'now', 'now', 'now')
            """);
        jdbc.update("""
            INSERT INTO wristband_bindings(id, wristband_id, member_id, status, duration_minutes, bound_at, started_at)
            VALUES (9, 8, 7, 'ACTIVE', 60, 'now', 'now')
            """);
        jdbc.update("""
            INSERT INTO wristband_charge_records(wristband_id, wristband_uid, duration_minutes,
                                                 unit_price_cents, amount_cents, charged_at)
            VALUES (8, '2283055618', 60, 100, 6000, 'now')
            """);
        jdbc.update("""
            INSERT INTO game_play_records(member_id, binding_id, wristband_uid, device_id,
                 external_session_id, participant_index, game_id, game_name, status, started_at, ended_at,
                 points_awarded, result_json)
            VALUES (7, 9, '2283055618', 'game-1', 'session-multi', 0, 'simple', 'Simple',
                    'COMPLETED', 'now', 'later', 10, '{"score":10}'),
                   (7, 9, '2283055618', 'game-1', 'session-multi', 1, 'simple', 'Simple',
                    'COMPLETED', 'now', 'later', 10, '{"score":10}')
            """);
        jdbc.update("""
            INSERT INTO operator_action_logs(operator_id, operator_username, operator_display_name,
                 action, target_type, target_id, summary_json, created_at)
            VALUES (1, 'admin', '出厂管理员', 'MEMBER_CREATED', 'MEMBER', '7', '{"phone":"13800138000"}', 'now')
            """);
        assertFilesContainAtLeastOnce(root.resolve("migration.db"),
                "13800138000", "测试会员甲", "2283055618");
        Map<String, Object> stateBefore = jdbc.queryForMap("SELECT * FROM database_state WHERE id=1");

        byte[] rawKey = java.util.Arrays.copyOf("migration-key-material-32-bytes!!"
                .getBytes(StandardCharsets.UTF_8), 32);
        DataKeyMaterial key = DataProtectionKeyManager.material(rawKey);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(key);
        when(keyManager.loadExisting()).thenReturn(key);
        ProtectedDataService protectedData = new ProtectedDataService(keyManager);
        new DataProtectionMigration(jdbc, keyManager, protectedData, Clock.systemUTC())
                .run(new DefaultApplicationArguments());

        Map<String, Object> member = jdbc.queryForMap("SELECT * FROM members WHERE id=7");
        assertThat(String.valueOf(member.get("phone"))).startsWith("enc:v1:").doesNotContain("13800138000");
        assertThat(member.get("phone_lookup_hash")).isEqualTo(protectedData.phoneLookupHash("13800138000"));
        assertThat(protectedData.decryptField("members", "name", member.get("name"))).isEqualTo("测试会员甲");
        assertThat(jdbc.queryForObject("SELECT member_id FROM wristband_bindings WHERE id=9", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT duration_minutes FROM wristbands WHERE id=8", Integer.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wristband_charge_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wristband_bindings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_action_logs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(points_awarded) FROM game_play_records", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForMap("SELECT * FROM database_state WHERE id=1"))
                .containsEntry("revision", stateBefore.get("revision"))
                .containsEntry("last_business_modified_at", stateBefore.get("last_business_modified_at"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM operator_accounts WHERE id=1", String.class))
                .isEqualTo("$2a$hash-kept");
        assertThat(jdbc.queryForObject("SELECT status FROM data_protection_state WHERE id=1", String.class))
                .isEqualTo("COMPLETE");
        assertFilesDoNotContain(root.resolve("migration.db"),
                "13800138000", "测试会员甲", "2283055618", "{\"phone\":\"13800138000\"}");
    }

    @Test
    void rollsBackAnInterruptedMigrationAndCanRetryWithoutMixedStorage() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("retry.db").toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO members(id, phone, name, status, created_at, updated_at, created_by)
            VALUES (1, '13800138001', '先迁移会员', 'ACTIVE', 'now', 'now', 'test'),
                   (2, 'enc:v1:broken', '故障会员', 'ACTIVE', 'now', 'now', 'test')
            """);
        DataKeyMaterial key = DataProtectionKeyManager.material(new byte[32]);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(key);
        when(keyManager.loadExisting()).thenReturn(key);
        ProtectedDataService protectedData = new ProtectedDataService(keyManager);
        DataProtectionMigration migration = new DataProtectionMigration(
                jdbc, keyManager, protectedData, Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), null);

        assertThatThrownBy(() -> migration.run(new DefaultApplicationArguments()))
                .hasMessageContaining("DATA_PROTECTION_INTEGRITY_FAILED");
        assertThat(jdbc.queryForObject("SELECT phone FROM members WHERE id=1", String.class))
                .isEqualTo("13800138001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_protection_state", Integer.class)).isZero();

        jdbc.update("UPDATE members SET phone='13800138002' WHERE id=2");
        migration.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT status FROM data_protection_state WHERE id=1", String.class))
                .isEqualTo("COMPLETE");
        assertThat(jdbc.queryForList("SELECT phone FROM members"))
                .allSatisfy(row -> assertThat(String.valueOf(row.get("phone"))).startsWith("enc:v1:"));
    }

    @Test
    void missingExistingKeyEntersBlockedMaintenanceStateWithoutCreatingReplacementData() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("missing-key.db").toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO data_protection_state(id, format_version, key_id, status, completed_at)
            VALUES (1, 1, 'missing-key-id', 'COMPLETE', 'now')
            """);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadExisting()).thenThrow(new IllegalStateException("DATA_PROTECTION_KEY_MISSING"));
        StartupGate gate = new StartupGate();
        new DataProtectionMigration(jdbc, keyManager, new ProtectedDataService(keyManager), Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), gate)
                .run(new DefaultApplicationArguments());

        assertThat(gate.status().state()).isEqualTo(BackupLifecycleState.BLOCKED);
        assertThat(gate.status().errorCode()).isEqualTo("DATA_PROTECTION_KEY_MISSING");
        assertThat(jdbc.queryForObject("SELECT key_id FROM data_protection_state WHERE id=1", String.class))
                .isEqualTo("missing-key-id");
    }

    @Test
    void mismatchedExistingKeyBlocksStartupWithoutChangingTheRecordedIdentity() throws Exception {
        DriverManagerDataSource dataSource = initialized("mismatched-key.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO data_protection_state(id, format_version, key_id, status, completed_at)
            VALUES (1, 1, 'database-key-id', 'COMPLETE', 'now')
            """);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadExisting()).thenReturn(DataProtectionKeyManager.material(new byte[32]));
        StartupGate gate = new StartupGate();

        new DataProtectionMigration(jdbc, keyManager, new ProtectedDataService(keyManager), Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), gate).run(new DefaultApplicationArguments());

        assertThat(gate.status().state()).isEqualTo(BackupLifecycleState.BLOCKED);
        assertThat(gate.status().errorCode()).isEqualTo("DATA_PROTECTION_KEY_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT key_id FROM data_protection_state WHERE id=1", String.class))
                .isEqualTo("database-key-id");
    }

    @Test
    void dpapiUnsealFailureHasAnExplicitMaintenanceReason() throws Exception {
        DriverManagerDataSource dataSource = initialized("dpapi-failure.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO data_protection_state(id, format_version, key_id, status, completed_at)
            VALUES (1, 1, 'database-key-id', 'COMPLETE', 'now')
            """);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadExisting()).thenThrow(new IllegalStateException("DATA_PROTECTION_KEY_READ_FAILED"));
        StartupGate gate = new StartupGate();

        new DataProtectionMigration(jdbc, keyManager, new ProtectedDataService(keyManager), Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), gate).run(new DefaultApplicationArguments());

        assertThat(gate.status().state()).isEqualTo(BackupLifecycleState.BLOCKED);
        assertThat(gate.status().errorCode()).isEqualTo("DATA_PROTECTION_KEY_UNAVAILABLE");
    }

    @Test
    void authenticatedCiphertextFailureBlocksStartup() throws Exception {
        DriverManagerDataSource dataSource = initialized("tampered.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataKeyMaterial key = DataProtectionKeyManager.material(new byte[32]);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(key);
        when(keyManager.loadExisting()).thenReturn(key);
        ProtectedDataService protectedData = new ProtectedDataService(keyManager);
        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800139999', '待篡改会员', 'ACTIVE', 'now', 'now', 'test')
            """);
        new DataProtectionMigration(jdbc, keyManager, protectedData, Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), null).run(new DefaultApplicationArguments());
        String encrypted = jdbc.queryForObject("SELECT phone FROM members", String.class);
        jdbc.update("UPDATE members SET phone=?", encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A"));
        StartupGate gate = new StartupGate();

        new DataProtectionMigration(jdbc, keyManager, protectedData, Clock.systemUTC(),
                new DataSourceTransactionManager(dataSource), gate).run(new DefaultApplicationArguments());

        assertThat(gate.status().state()).isEqualTo(BackupLifecycleState.BLOCKED);
        assertThat(gate.status().errorCode()).isEqualTo("DATA_PROTECTION_INTEGRITY_FAILED");
    }

    private DriverManagerDataSource initialized(String name) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve(name).toAbsolutePath());
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return dataSource;
    }

    private static void assertFilesDoNotContain(java.nio.file.Path database, String... secrets) throws Exception {
        for (java.nio.file.Path candidate : java.util.List.of(database,
                java.nio.file.Path.of(database + "-wal"), java.nio.file.Path.of(database + "-shm"))) {
            if (!java.nio.file.Files.isRegularFile(candidate)) continue;
            byte[] content = java.nio.file.Files.readAllBytes(candidate);
            for (String secret : secrets) {
                assertThat(indexOf(content, secret.getBytes(StandardCharsets.UTF_8)))
                        .as("%s must not contain plaintext %s", candidate, secret)
                        .isEqualTo(-1);
            }
        }
    }

    private static void assertFilesContainAtLeastOnce(java.nio.file.Path database, String... secrets) throws Exception {
        java.util.List<java.nio.file.Path> candidates = java.util.List.of(database,
                java.nio.file.Path.of(database + "-wal"), java.nio.file.Path.of(database + "-shm"));
        for (String secret : secrets) {
            boolean found = false;
            for (java.nio.file.Path candidate : candidates) {
                if (!java.nio.file.Files.isRegularFile(candidate)) continue;
                if (indexOf(java.nio.file.Files.readAllBytes(candidate), secret.getBytes(StandardCharsets.UTF_8)) >= 0) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("plaintext scanner positive control for %s", secret).isTrue();
        }
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
