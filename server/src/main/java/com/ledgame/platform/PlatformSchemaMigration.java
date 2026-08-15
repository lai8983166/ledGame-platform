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
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(game_play_records)");
        boolean hasScoringPolicy = columns.stream()
                .anyMatch(column -> "scoring_policy".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasScoringPolicy) {
            jdbc.execute("ALTER TABLE game_play_records ADD COLUMN scoring_policy TEXT");
        }
    }
}
