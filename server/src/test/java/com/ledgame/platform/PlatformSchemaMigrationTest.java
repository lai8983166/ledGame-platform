package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
}
