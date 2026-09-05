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
    public static final int CURRENT_SCHEMA_VERSION = 2;
    static final List<String> REVISION_TRACKED_TABLES = List.of(
            "members", "wristbands", "wristband_charge_records", "wristband_bindings",
            "game_play_records", "room_settings", "store_feature_settings",
            "operator_accounts", "operator_action_logs");
    private final JdbcTemplate jdbc;

    public PlatformSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
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
