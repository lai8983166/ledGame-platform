package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OperatorRoleMigrationTest {
    @TempDir Path root;

    @Test
    void migratesLegacyOperatorInPlaceAndPreservesAuditRelationshipAndPasswordHash() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("roles.db").toAbsolutePath()));
        jdbc.execute("""
            CREATE TABLE operator_accounts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              username TEXT NOT NULL COLLATE NOCASE UNIQUE,
              display_name TEXT NOT NULL,
              password_hash TEXT NOT NULL,
              account_type TEXT NOT NULL CHECK (account_type IN ('FACTORY_ADMIN', 'OPERATOR')),
              enabled INTEGER NOT NULL DEFAULT 1,
              created_by_operator_id INTEGER REFERENCES operator_accounts(id),
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
            """);
        jdbc.execute("""
            CREATE TABLE operator_action_logs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              operator_id INTEGER NOT NULL REFERENCES operator_accounts(id),
              operator_username TEXT NOT NULL, operator_display_name TEXT NOT NULL,
              action TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT,
              summary_json TEXT, created_at TEXT NOT NULL)
            """);
        jdbc.update("""
            INSERT INTO operator_accounts(id, username, display_name, password_hash, account_type,
                                          enabled, created_at, updated_at)
            VALUES (1, 'admin', '出厂管理员', 'factory-hash', 'FACTORY_ADMIN', 1, 'old', 'old'),
                   (2, 'counter', '前台', 'clerk-hash', 'OPERATOR', 0, 'old', 'old')
            """);
        jdbc.update("""
            INSERT INTO operator_action_logs(operator_id, operator_username, operator_display_name,
                                             action, target_type, created_at)
            VALUES (2, 'counter', '前台', 'TEST', 'ACCOUNT', 'old')
            """);

        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForMap("SELECT * FROM operator_accounts WHERE id=2"))
                .containsEntry("account_type", "CLERK")
                .containsEntry("password_hash", "clerk-hash")
                .containsEntry("enabled", 0);
        assertThat(jdbc.queryForObject("SELECT operator_id FROM operator_action_logs", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class))
                .isEqualTo(PlatformSchemaMigration.CURRENT_SCHEMA_VERSION);
    }
}
