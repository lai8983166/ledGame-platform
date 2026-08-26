package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
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
@Import(LeaderboardApiIntegrationTest.TestClockConfiguration.class)
class LeaderboardApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
        registry.add("ledgame.time-zone", () -> "Asia/Shanghai");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;

    @BeforeEach
    void clearData() {
        clock.set(Instant.parse("2026-08-09T02:00:00Z"));
        jdbc.update("DELETE FROM wristband_charge_records");
        jdbc.update("DELETE FROM game_play_records");
        jdbc.update("DELETE FROM wristband_bindings");
        jdbc.update("DELETE FROM wristbands");
        jdbc.update("DELETE FROM members");
    }

    @AfterAll
    void deleteDatabase() throws IOException {
        if (dataSource instanceof HikariDataSource hikariDataSource) hikariDataSource.close();
        Files.deleteIfExists(DATABASE_PATH);
    }

    @Test
    void aggregatesCalendarPeriodsCompletedGamesAndCompetitionRanks() {
        long alpha = member("13000131001", "并列甲", "ACTIVE");
        long beta = member("13000131002", "并列乙", "ACTIVE");
        long third = member("13000131003", "第三名", "ACTIVE");
        long frozen = member("13000131004", "冻结玩家", "FROZEN");

        play(alpha, "COMPLETED", "2026-08-09T01:00:00Z", 100);
        play(alpha, "COMPLETED", "2026-08-01T01:00:00Z", 50);
        play(alpha, "COMPLETED", "2026-01-15T01:00:00Z", 25);
        play(alpha, "ABORTED", "2026-08-09T01:30:00Z", 999);
        play(beta, "COMPLETED", "2026-08-09T01:15:00Z", 100);
        play(third, "COMPLETED", "2026-08-09T01:20:00Z", 10);
        play(frozen, "COMPLETED", "2026-08-09T01:25:00Z", 500);

        Map<String, Object> day = get("/api/leaderboard?period=day").getBody();
        assertThat(day).containsEntry("period", "day")
                .containsEntry("periodStart", "2026-08-09T00:00:00+08:00")
                .containsEntry("periodEnd", "2026-08-10T00:00:00+08:00");
        List<Map<String, Object>> dayEntries = maps(day.get("entries"));
        assertThat(dayEntries).extracting(row -> number(row.get("points")))
                .containsExactly(100L, 100L, 10L);
        assertThat(dayEntries).extracting(row -> number(row.get("rank")))
                .containsExactly(1L, 1L, 3L);
        assertThat(dayEntries).extracting(row -> number(row.get("memberId")))
                .containsExactly(alpha, beta, third);

        Map<String, Object> monthFirst = maps(get("/api/leaderboard?period=month").getBody().get("entries")).get(0);
        assertThat(number(monthFirst.get("points"))).isEqualTo(150L);
        assertThat(number(monthFirst.get("completedGames"))).isEqualTo(2L);
        Map<String, Object> yearFirst = maps(get("/api/leaderboard?period=year").getBody().get("entries")).get(0);
        assertThat(number(yearFirst.get("points"))).isEqualTo(175L);
        assertThat(number(yearFirst.get("completedGames"))).isEqualTo(3L);
    }

    @Test
    void returnsEmptyListWhenNoMemberCompletedAGameInThePeriod() {
        member("13000131005", "零积分玩家", "ACTIVE");
        assertThat(maps(get("/api/leaderboard?period=day").getBody().get("entries"))).isEmpty();
    }

    @Test
    void rejectsUnknownPeriodWithStableCode() {
        ResponseEntity<Map<String, Object>> response = get("/api/leaderboard?period=week");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_LEADERBOARD_PERIOD");
    }

    private long member(String phone, String name, String status) {
        String now = "2026-01-01T00:00:00Z";
        jdbc.update("INSERT INTO members(phone,name,avatar_id,status,created_at,updated_at,created_by) VALUES (?,?,? ,?,?,?,?)",
                phone, name, "nova", status, now, now, "leaderboard-test");
        return jdbc.queryForObject("SELECT id FROM members WHERE phone=?", Long.class, phone);
    }

    private void play(long memberId, String status, String endedAt, int points) {
        String uid = "9" + memberId + jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records", Integer.class);
        jdbc.update("INSERT INTO wristbands(card_uid,status,duration_minutes,created_at,updated_at) VALUES (?,'ACTIVE',60,?,?)",
                uid, endedAt, endedAt);
        long wristbandId = jdbc.queryForObject("SELECT id FROM wristbands WHERE card_uid=?", Long.class, uid);
        jdbc.update("INSERT INTO wristband_bindings(wristband_id,member_id,status,duration_minutes,bound_at,started_at) VALUES (?,?,'ACTIVE',60,?,?)",
                wristbandId, memberId, endedAt, endedAt);
        long bindingId = jdbc.queryForObject("SELECT id FROM wristband_bindings WHERE wristband_id=?", Long.class, wristbandId);
        jdbc.update("INSERT INTO game_play_records(member_id,binding_id,wristband_uid,device_id,external_session_id,participant_index,game_id,game_name,status,started_at,ended_at,points_awarded) VALUES (?,?,?,?,?,0,'test','排行榜测试',?,?,?,?)",
                memberId, bindingId, uid, "leaderboard-device", "session-" + uid, status,
                Instant.parse(endedAt).minusSeconds(60).toString(), endedAt, points);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-leaderboard-test-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean @Primary MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-09T02:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
