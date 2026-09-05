package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class SqliteBusinessConcurrencyIntegrationTest {
    private static final Path TEST_ROOT = createTestRoot();
    private static final Path DATABASE_PATH = TEST_ROOT.resolve("platform.db");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
        registry.add("ledgame.sqlite.busy-timeout-millis", () -> 1000);
        registry.add("ledgame.database-backup.enabled", () -> false);
        registry.add("ledgame.room-connection.enabled", () -> false);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private SqliteOnlineBackup onlineBackup;
    @Autowired private DatabaseFileInspector databaseInspector;

    @BeforeEach
    void clearBusinessData() {
        jdbc.update("DELETE FROM wristband_charge_records");
        jdbc.update("DELETE FROM game_play_records");
        jdbc.update("DELETE FROM wristband_bindings");
        jdbc.update("DELETE FROM wristbands");
        jdbc.update("DELETE FROM members");
    }

    @Test
    void commitsAtLeastEightSimultaneousChargesExactlyOnceWithoutServerErrors() throws Exception {
        List<Callable<ResponseEntity<Map<String, Object>>>> requests = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String uid = "7400000000000000000" + index;
            int durationMinutes = 30 + index;
            requests.add(() -> post("/api/wristbands/charge", Map.of(
                    "uid", uid, "durationMinutes", durationMinutes)));
        }

        Instant started = Instant.now();
        List<ResponseEntity<Map<String, Object>>> responses = runTogether(requests);

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode().is5xxServerError()).isFalse();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        });
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristbands WHERE card_uid LIKE '7400000000000000000%'",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid LIKE '7400000000000000000%'",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
                SELECT w.id
                  FROM wristbands w
                  LEFT JOIN wristband_charge_records c ON c.wristband_id=w.id
                 WHERE w.card_uid LIKE '7400000000000000000%'
                 GROUP BY w.id
                HAVING COUNT(c.id) <> 1
            ) invalid_wristbands
            """, Integer.class)).isZero();
    }

    @Test
    void concurrentRegistrationAndGameFlowsKeepAllRelationshipsSessionsAndPoints() throws Exception {
        List<Callable<FlowResult>> flows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int participant = index;
            flows.add(() -> executeFlow(participant, participant >= 4));
        }

        Instant started = Instant.now();
        List<FlowResult> results = runTogether(flows);

        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));
        assertThat(results).hasSize(8);
        assertThat(results.stream().map(FlowResult::memberId)).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM members WHERE phone LIKE '1319000000%'",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristbands WHERE card_uid LIKE '7500000000000000000%'",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM wristband_bindings b
              JOIN wristbands w ON w.id=b.wristband_id
              JOIN members m ON m.id=b.member_id
             WHERE w.card_uid LIKE '7500000000000000000%'
               AND substr(w.card_uid, 20, 1)=substr(m.phone, 11, 1)
            """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id LIKE 'concurrent-session-%'",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM game_play_records
             WHERE external_session_id LIKE 'concurrent-session-%'
               AND status='COMPLETED' AND points_awarded=64
            """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
            SELECT COALESCE(SUM(points_awarded), 0) FROM game_play_records
             WHERE external_session_id LIKE 'concurrent-session-%'
            """, Long.class)).isEqualTo(256L);
    }

    @Test
    void onlineBackupOverlappingBusinessWritesRemainsConsistentAndCausesNoHttp500() throws Exception {
        assertOk(post("/api/wristbands/charge", Map.of(
                "uid", "76000000000000000000", "durationMinutes", 20)));
        Path backupPath = TEST_ROOT.resolve("overlap-backup.db");

        List<Callable<Object>> operations = new ArrayList<>();
        for (int index = 1; index <= 16; index++) {
            String uid = "760000000000000000" + String.format("%02d", index);
            operations.add(() -> post("/api/wristbands/charge", Map.of(
                    "uid", uid, "durationMinutes", 20)));
        }
        operations.add(() -> {
            onlineBackup.create(backupPath);
            return backupPath;
        });

        List<Object> results = runTogether(operations);
        for (Object result : results) {
            if (result instanceof ResponseEntity<?> response) assertOk(response);
        }
        assertThat(databaseInspector.inspect(backupPath).valid()).isTrue();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backupPath.toAbsolutePath());
             var statement = connection.createStatement()) {
            int backupWristbands;
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM wristbands WHERE card_uid LIKE '760000000000000000%'")) {
                backupWristbands = rows.getInt(1);
            }
            int backupCharges;
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid LIKE '760000000000000000%'")) {
                backupCharges = rows.getInt(1);
            }
            assertThat(backupWristbands).isBetween(1, 17);
            assertThat(backupCharges).isEqualTo(backupWristbands);
            try (var rows = statement.executeQuery("""
                SELECT COUNT(*)
                  FROM wristband_charge_records c
                  LEFT JOIN wristbands w ON w.id=c.wristband_id
                 WHERE c.wristband_uid LIKE '760000000000000000%' AND w.id IS NULL
                """)) {
                assertThat(rows.getInt(1)).isZero();
            }
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristbands WHERE card_uid LIKE '760000000000000000%'",
                Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristband_charge_records WHERE wristband_uid LIKE '760000000000000000%'",
                Integer.class)).isEqualTo(17);
    }

    private FlowResult executeFlow(int index, boolean playGame) {
        String suffix = Integer.toString(index);
        String phone = "1319000000" + suffix;
        String uid = "7500000000000000000" + suffix;

        assertOk(post("/api/wristbands/charge", Map.of("uid", uid, "durationMinutes", 60)));
        ResponseEntity<Map<String, Object>> member = post("/api/members", Map.of(
                "phone", phone,
                "name", "concurrent member " + suffix,
                "createdBy", "sqlite-concurrency-test"));
        assertOk(member);
        long memberId = number(member.getBody().get("id"));
        assertOk(post("/api/wristbands/bind", Map.of("uid", uid, "memberId", memberId)));

        if (playGame) {
            assertOk(post("/api/game-access/activate", Map.of(
                    "uid", uid, "deviceId", "concurrent-device-" + suffix,
                    "roomId", "concurrent-room-" + suffix)));
            ResponseEntity<Map<String, Object>> play = post("/api/game-plays/start", Map.of(
                    "uid", uid,
                    "deviceId", "concurrent-device-" + suffix,
                    "roomId", "concurrent-room-" + suffix,
                    "externalSessionId", "concurrent-session-" + suffix,
                    "gameId", "simple",
                    "gameName", "concurrent game"));
            assertOk(play);
            ResponseEntity<Map<String, Object>> settled = put(
                    "/api/game-plays/" + number(play.getBody().get("id")) + "/result",
                    Map.of("success", true,
                            "terminationReason", "NATURAL_COMPLETION",
                            "rawScore", 64,
                            "pointsAwarded", 999));
            assertOk(settled);
            assertThat(settled.getBody()).containsEntry("status", "COMPLETED")
                    .containsEntry("pointsAwarded", 64);
        }

        ResponseEntity<Map<String, Object>> info = get("/api/player-info?phone=" + phone);
        assertOk(info);
        assertThat(number(map(info.getBody().get("profile")).get("id"))).isEqualTo(memberId);
        return new FlowResult(memberId, uid, playGame);
    }

    private <T> List<T> runTogether(List<? extends Callable<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        try {
            List<Future<T>> futures = operations.stream().map(operation -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test start barrier timed out");
                }
                return operation.call();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> put(String path, Map<String, Object> body) {
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
    }

    private void assertOk(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    @AfterAll
    void closeAndDeleteDatabase() throws IOException {
        if (dataSource instanceof HikariDataSource hikari) hikari.close();
        try (var paths = Files.walk(TEST_ROOT)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path createTestRoot() {
        try {
            return Files.createTempDirectory("ledgame-sqlite-business-concurrency-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record FlowResult(long memberId, String uid, boolean gameFlow) {}
}
