package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteLockHandlingIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
        registry.add("ledgame.sqlite.busy-timeout-millis", () -> 500);
        registry.add("ledgame.database-backup.enabled", () -> false);
        registry.add("ledgame.room-connection.enabled", () -> false);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void clearBusinessData() {
        jdbc.update("DELETE FROM wristband_charge_records");
        jdbc.update("DELETE FROM game_play_records");
        jdbc.update("DELETE FROM wristband_bindings");
        jdbc.update("DELETE FROM wristbands");
        jdbc.update("DELETE FROM members");
    }

    @Test
    void waitsForAShortExternalWriteLockThenCommitsNormally() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
             Statement statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            Future<ResponseEntity<Map<String, Object>>> pending = executor.submit(
                    () -> charge("73000000000000000001"));
            Thread.sleep(100);
            statement.execute("COMMIT");

            ResponseEntity<Map<String, Object>> response = pending.get();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wristbands WHERE card_uid='73000000000000000001'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid='73000000000000000001'",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsDatabaseBusyAfterTheBoundedWaitAndRollsBackTheWholeOperation() throws Exception {
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
             Statement statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            Instant started = Instant.now();
            ResponseEntity<Map<String, Object>> response;
            try {
                response = charge("73000000000000000002");
            } finally {
                statement.execute("ROLLBACK");
            }

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody())
                    .containsEntry("code", "DATABASE_BUSY")
                    .containsEntry("message", "数据库正在忙，请稍后重试；如果持续出现，请检查是否有其他程序正在占用数据库文件");
            assertThat(Duration.between(started, Instant.now())).isBetween(
                    Duration.ofMillis(300), Duration.ofSeconds(3));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wristbands WHERE card_uid='73000000000000000002'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid='73000000000000000002'",
                    Integer.class)).isZero();

            ResponseEntity<Map<String, Object>> next = charge("73000000000000000003");
            assertThat(next.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid='73000000000000000003'",
                    Integer.class)).isEqualTo(1);
        }
    }

    private ResponseEntity<Map<String, Object>> charge(String uid) {
        return http.exchange("/api/wristbands/charge", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", uid, "durationMinutes", 30)),
                new ParameterizedTypeReference<>() {});
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
            Path path = Files.createTempFile("ledgame-sqlite-lock-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
