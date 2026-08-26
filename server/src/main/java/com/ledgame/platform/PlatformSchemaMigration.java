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
    }
}
