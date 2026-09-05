package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteInMemoryCompatibilityIntegrationTest {
    private static final String DATABASE_NAME = "sqlite-concurrency-memory-compatibility";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:file:" + DATABASE_NAME + "?mode=memory&cache=shared");
        registry.add("ledgame.sqlite.busy-timeout-millis", () -> 600);
        registry.add("ledgame.database-backup.enabled", () -> false);
        registry.add("ledgame.room-connection.enabled", () -> false);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void startsAndWritesUsingTheSharedInMemoryTestDatabase() {
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class))
                .isEqualToIgnoringCase("memory");
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(600);

        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13900000003', 'memory member', 'ACTIVE',
                    '2026-09-05T00:00:00Z', '2026-09-05T00:00:00Z', 'memory-test')
            """);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM members WHERE phone='13900000003'",
                Integer.class)).isEqualTo(1);
    }

    @AfterAll
    void closeDatabase() {
        if (dataSource instanceof HikariDataSource hikari) hikari.close();
    }
}
