package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

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
class SqliteConcurrencyPolicyIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
        registry.add("ledgame.sqlite.busy-timeout-millis", () -> 750);
        registry.add("ledgame.database-backup.enabled", () -> false);
        registry.add("ledgame.room-connection.enabled", () -> false);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void appliesSingleConnectionWalAndConfiguredBusyTimeoutToTheRealDatasource() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(750);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.unwrap(SQLiteConnection.class).getConnectionConfig().getTransactionMode())
                    .isEqualTo(SQLiteConfig.TransactionMode.IMMEDIATE);
        } catch (Exception exception) {
            throw new AssertionError("无法读取 SQLite 事务模式", exception);
        }
    }

    @Test
    void preservesExistingBusinessDataAndAcceptsNewWritesWithoutMigration() {
        assertThat(jdbc.queryForObject(
                "SELECT name FROM members WHERE phone='13900000001'",
                String.class)).isEqualTo("existing member");

        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13900000002', 'new member', 'ACTIVE',
                    '2026-09-05T00:00:00Z', '2026-09-05T00:00:00Z', 'compatibility-test')
            """);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(2);
    }

    @AfterAll
    void closeAndDeleteDatabase() throws IOException {
        if (dataSource instanceof HikariDataSource hikari) hikari.close();
        Files.deleteIfExists(DATABASE_PATH.resolveSibling(DATABASE_PATH.getFileName() + "-wal"));
        Files.deleteIfExists(DATABASE_PATH.resolveSibling(DATABASE_PATH.getFileName() + "-shm"));
        Files.deleteIfExists(DATABASE_PATH);
    }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-sqlite-policy-", ".db");
            Files.deleteIfExists(path);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE members (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        phone TEXT NOT NULL,
                        name TEXT NOT NULL,
                        avatar_id TEXT,
                        birthday TEXT,
                        gender TEXT,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        deleted_at TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        created_by TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
                    VALUES ('13900000001', 'existing member', 'ACTIVE',
                            '2026-09-04T00:00:00Z', '2026-09-04T00:00:00Z', 'old-version')
                    """);
            }
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
