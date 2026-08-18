package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class PlatformSchemaMigrationTest {
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
