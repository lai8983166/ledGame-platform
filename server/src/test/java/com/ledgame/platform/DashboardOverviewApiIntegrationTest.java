package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(DashboardOverviewApiIntegrationTest.TestClockConfiguration.class)
class DashboardOverviewApiIntegrationTest {
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
    void reportsRealActiveMembersSuccessfulChargesAndOneYuanPerMinuteForStoreDay() {
        member("13000132001", "存量会员", "ACTIVE", null, "2026-08-08T15:59:59Z");
        member("13000132002", "今日新增", "ACTIVE", null, "2026-08-08T16:00:00Z");
        member("13000132003", "已删除会员", "FROZEN", "2026-08-09T01:00:00Z", "2026-08-09T00:00:00Z");

        assertThat(charge("2283055801", 30).getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.set(Instant.parse("2026-08-09T03:00:00Z"));
        assertThat(charge("2283055802", 60).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> rejected = charge("2283055802", 10);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        jdbc.update("INSERT INTO wristband_charge_records(wristband_id,wristband_uid,duration_minutes,unit_price_cents,amount_cents,charged_at) SELECT id,card_uid,20,100,2000,'2026-08-08T15:59:59Z' FROM wristbands WHERE card_uid='2283055801'");

        ResponseEntity<Map<String, Object>> response = http.exchange(
                "/api/dashboard/overview", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("totalMembers", 2)
                .containsEntry("newMembersToday", 1)
                .containsEntry("wristbandsChargedToday", 2)
                .containsEntry("revenueTodayCents", 9000)
                .containsEntry("periodStart", "2026-08-09T00:00:00+08:00")
                .containsEntry("periodEnd", "2026-08-10T00:00:00+08:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wristband_charge_records", Integer.class)).isEqualTo(3);
    }

    private void member(String phone, String name, String status, String deletedAt, String createdAt) {
        jdbc.update("INSERT INTO members(phone,name,status,deleted_at,created_at,updated_at,created_by) VALUES (?,?,?,?,?,?,?)",
                phone, name, status, deletedAt, createdAt, createdAt, "dashboard-test");
    }

    private ResponseEntity<Map<String, Object>> charge(String uid, int minutes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/wristbands/charge", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", uid, "durationMinutes", minutes), headers),
                new ParameterizedTypeReference<>() {});
    }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-dashboard-test-", ".db");
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
