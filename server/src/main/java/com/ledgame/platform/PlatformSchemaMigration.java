package com.ledgame.platform;

import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformSchemaMigration implements ApplicationRunner {
    public static final int CURRENT_SCHEMA_VERSION = 4;
    static final List<String> REVISION_TRACKED_TABLES = List.of(
            "members", "wristbands", "wristband_charge_records", "wristband_bindings",
            "game_play_records", "room_settings", "store_feature_settings", "store_settings",
            "operator_accounts", "operator_action_logs");
    private final JdbcTemplate jdbc;

    public PlatformSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateOperatorAccounts();
        migrateStoreSettings();
        migrateChargeAuditFields();
        List<Map<String, Object>> memberColumns = jdbc.queryForList("PRAGMA table_info(members)");
        boolean hasDeletedAt = memberColumns.stream()
                .anyMatch(column -> "deleted_at".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!memberColumns.isEmpty() && !hasDeletedAt) {
            jdbc.execute("ALTER TABLE members ADD COLUMN deleted_at TEXT");
        }

        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(game_play_records)");
        boolean hasScoringPolicy = columns.stream()
                .anyMatch(column -> "scoring_policy".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!columns.isEmpty() && !hasScoringPolicy) {
            jdbc.execute("ALTER TABLE game_play_records ADD COLUMN scoring_policy TEXT");
        }
        boolean hasParticipantIndex = columns.stream()
                .anyMatch(column -> "participant_index".equalsIgnoreCase(String.valueOf(column.get("name"))));
        boolean hasMultiplayerColumns = List.of("device_id", "external_session_id", "binding_id").stream()
                .allMatch(required -> columns.stream().anyMatch(
                        column -> required.equalsIgnoreCase(String.valueOf(column.get("name")))));
        if (hasMultiplayerColumns && !hasParticipantIndex) {
            jdbc.execute("ALTER TABLE game_play_records ADD COLUMN participant_index INTEGER NOT NULL DEFAULT 0");
        }
        if (hasMultiplayerColumns) {
            jdbc.execute("DROP INDEX IF EXISTS ux_game_play_external_session");
            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_binding
                    ON game_play_records(device_id, external_session_id, binding_id)
                """);
            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_participant
                    ON game_play_records(device_id, external_session_id, participant_index)
                """);
        }
        ensureDatabaseStateAndRevisionTriggers();
        jdbc.execute("PRAGMA user_version=" + CURRENT_SCHEMA_VERSION);
    }

    private void migrateStoreSettings() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                app_title TEXT,
                app_icon_path TEXT,
                app_icon_sha256 TEXT,
                unit_price_cents INTEGER NOT NULL DEFAULT 100 CHECK (unit_price_cents > 0),
                secondary_display_enabled INTEGER NOT NULL DEFAULT 0 CHECK (secondary_display_enabled IN (0, 1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL)
            """);
        addColumnIfMissing("store_settings", "app_title", "TEXT");
        addColumnIfMissing("store_settings", "app_icon_path", "TEXT");
        addColumnIfMissing("store_settings", "app_icon_sha256", "TEXT");
        addColumnIfMissing("store_settings", "unit_price_cents", "INTEGER NOT NULL DEFAULT 100");
        addColumnIfMissing("store_settings", "secondary_display_enabled", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("store_settings", "created_at", "TEXT");
        addColumnIfMissing("store_settings", "updated_at", "TEXT");
        String now = java.time.Instant.now().toString();
        jdbc.update("""
            INSERT OR IGNORE INTO store_settings(
                id, app_title, app_icon_path, app_icon_sha256,
                unit_price_cents, secondary_display_enabled, created_at, updated_at)
            VALUES (1, NULL, NULL, NULL, 100, 0, ?, ?)
            """, now, now);
        jdbc.update("UPDATE store_settings SET unit_price_cents=100 WHERE unit_price_cents IS NULL OR unit_price_cents <= 0");
        jdbc.update("UPDATE store_settings SET secondary_display_enabled=0 WHERE secondary_display_enabled IS NULL");
    }

    private void migrateChargeAuditFields() {
        if (!tableExists("wristband_charge_records")) return;
        addColumnIfMissing("wristband_charge_records", "operator_id", "INTEGER");
        addColumnIfMissing("wristband_charge_records", "operator_username", "TEXT");
        addColumnIfMissing("wristband_charge_records", "operator_display_name", "TEXT");
        addColumnIfMissing("wristband_charge_records", "issued_at", "TEXT");
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean present = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
        if (!present) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void migrateOperatorAccounts() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(operator_accounts)");
        if (columns.isEmpty()) return;
        boolean hasDeletedAt = columns.stream()
                .anyMatch(column -> "deleted_at".equalsIgnoreCase(String.valueOf(column.get("name"))));
        String createSql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='operator_accounts'", String.class);
        boolean oldRoleConstraint = createSql != null && createSql.contains("'OPERATOR'");
        if (!oldRoleConstraint) {
            if (!hasDeletedAt) jdbc.execute("ALTER TABLE operator_accounts ADD COLUMN deleted_at TEXT");
            return;
        }

        jdbc.execute("PRAGMA foreign_keys=OFF");
        try {
            jdbc.execute("""
                CREATE TABLE operator_accounts_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    display_name TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    account_type TEXT NOT NULL CHECK (account_type IN ('FACTORY_ADMIN', 'STORE_MANAGER', 'CLERK')),
                    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                    deleted_at TEXT,
                    created_by_operator_id INTEGER REFERENCES operator_accounts_v3(id),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL)
                """);
            String deletedAtExpression = hasDeletedAt ? "deleted_at" : "NULL";
            jdbc.execute("""
                INSERT INTO operator_accounts_v3(
                    id, username, display_name, password_hash, account_type, enabled,
                    deleted_at, created_by_operator_id, created_at, updated_at)
                SELECT id, username, display_name, password_hash,
                       CASE account_type WHEN 'OPERATOR' THEN 'CLERK' ELSE account_type END,
                       enabled, %s, created_by_operator_id, created_at, updated_at
                  FROM operator_accounts
                """.formatted(deletedAtExpression));
            jdbc.execute("DROP TABLE operator_accounts");
            jdbc.execute("ALTER TABLE operator_accounts_v3 RENAME TO operator_accounts");
        } finally {
            jdbc.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void ensureDatabaseStateAndRevisionTriggers() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS database_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                instance_id TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                last_business_modified_at TEXT NOT NULL,
                imported_from_revision INTEGER,
                imported_at TEXT)
            """);
        jdbc.update("""
            INSERT OR IGNORE INTO database_state(
                id, instance_id, revision, last_business_modified_at,
                imported_from_revision, imported_at)
            VALUES (1, lower(hex(randomblob(16))), 0,
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), NULL, NULL)
            """);
        for (String table : REVISION_TRACKED_TABLES) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            createRevisionTrigger(table, "insert", "INSERT");
            createRevisionTrigger(table, "update", "UPDATE");
            createRevisionTrigger(table, "delete", "DELETE");
        }
    }

    private void createRevisionTrigger(String table, String suffix, String operation) {
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS backup_revision_" + table + "_" + suffix
                + " AFTER " + operation + " ON " + table + " BEGIN "
                + "UPDATE database_state SET revision=revision+1, "
                + "last_business_modified_at=strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id=1; END");
    }
}
