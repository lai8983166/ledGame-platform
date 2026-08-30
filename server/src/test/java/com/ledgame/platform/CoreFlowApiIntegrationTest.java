package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(CoreFlowApiIntegrationTest.TestClockConfiguration.class)
class CoreFlowApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
    }

    @AfterAll
    void deleteDatabase() throws IOException {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
        Files.deleteIfExists(DATABASE_PATH);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void clearData() {
        clock.set(Instant.parse("2026-08-09T02:00:00Z"));
        jdbc.update("DELETE FROM wristband_charge_records");
        jdbc.update("DELETE FROM game_play_records");
        jdbc.update("DELETE FROM wristband_bindings");
        jdbc.update("DELETE FROM wristbands");
        jdbc.update("DELETE FROM members");
    }

    @Test
    void createsAndFindsMemberAndRejectsDuplicateActivePhone() {
        ResponseEntity<Map<String, Object>> created = post("/api/members", Map.of(
                "phone", "138 0013 8000",
                "name", "测试玩家",
                "avatarId", "avatar-01",
                "birthday", "2000-01-02",
                "gender", "secret",
                "createdBy", "test"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).containsEntry("phone", "13800138000");
        assertThat(created.getBody()).containsEntry("name", "测试玩家");

        ResponseEntity<List<Map<String, Object>>> found = http.exchange(
                "/api/members?phone=13800138000",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(found.getBody()).hasSize(1);
        assertThat(found.getBody().get(0)).containsEntry("avatarId", "avatar-01");

        ResponseEntity<Map<String, Object>> duplicate = post("/api/members", Map.of(
                "phone", "13800138000",
                "name", "另一玩家"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("message", "该手机号已经注册");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(1);
    }

    @Test
    void chargesPhysicalUidRejectsDuplicateAndClearsUnboundBalance() {
        ResponseEntity<Map<String, Object>> charged = post("/api/wristbands/charge", Map.of(
                "uid", "2283055618",
                "durationMinutes", 60));

        assertThat(charged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(charged.getBody()).containsEntry("uid", "2283055618");
        assertThat(charged.getBody()).containsEntry("status", "CHARGED");
        assertThat(charged.getBody()).containsEntry("durationMinutes", 60);

        ResponseEntity<Map<String, Object>> duplicate = post("/api/wristbands/charge", Map.of(
                "uid", "2283055618",
                "durationMinutes", 90));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("message").toString()).contains("不能重复充时");

        ResponseEntity<Map<String, Object>> cleared = post(
                "/api/wristbands/clear",
                Map.of("uid", "2283055618"));
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).containsEntry("status", "IN_STOCK");
        assertThat(cleared.getBody().get("durationMinutes")).isNull();
    }

    @Test
    void reclaimsExpiredWristbandBackToStockAndAllowsChargingAgain() {
        long memberId = createReadyWristband("13900139000", "回收玩家", 30);
        ResponseEntity<Map<String, Object>> activated = post("/api/game-access/activate", Map.of(
                "uid", "2283055618", "deviceId", "game-01"));
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofMinutes(31));
        ResponseEntity<Map<String, Object>> reclaimed = post("/api/wristbands/reclaim", Map.of("uid", "2283055618"));
        assertThat(reclaimed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reclaimed.getBody()).containsEntry("status", "IN_STOCK");
        assertThat(reclaimed.getBody().get("durationMinutes")).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM wristband_bindings WHERE member_id=?", String.class, memberId))
                .isEqualTo("EXPIRED");

        ResponseEntity<Map<String, Object>> chargedAgain = post("/api/wristbands/charge", Map.of(
                "uid", "2283055618", "durationMinutes", 45));
        assertThat(chargedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chargedAgain.getBody()).containsEntry("status", "CHARGED");
    }

    @Test
    void bindsChargedWristbandRejectsDuplicateWithExactMessageAndUnbindsReady() {
        long memberId = number(post("/api/members", Map.of(
                "phone", "13900139000",
                "name", "绑定玩家")).getBody().get("id"));
        post("/api/wristbands/charge", Map.of("uid", "2283055618", "durationMinutes", 45));

        ResponseEntity<Map<String, Object>> bound = post("/api/wristbands/bind", Map.of(
                "uid", "2283055618",
                "memberId", memberId));
        assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bound.getBody()).containsEntry("status", "READY");
        assertThat(number(bound.getBody().get("memberId"))).isEqualTo(memberId);
        assertThat(bound.getBody()).containsEntry("memberName", "绑定玩家");

        ResponseEntity<Map<String, Object>> duplicate = post("/api/wristbands/bind", Map.of(
                "uid", "2283055618",
                "memberId", memberId));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("message", "此手环已绑定");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristband_bindings WHERE status = 'READY'",
                Integer.class)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> unbound = post(
                "/api/wristbands/unbind",
                Map.of("uid", "2283055618"));
        assertThat(unbound.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unbound.getBody()).containsEntry("status", "IN_STOCK");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wristband_bindings WHERE status = 'RETURNED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void listsRealWristbandBindingAndChargeRecords() {
        long memberId = createReadyWristband("13900139000", "流水玩家", 45);

        ResponseEntity<List<Map<String, Object>>> bindings = http.exchange(
                "/api/records/wristband-bindings",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(bindings.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bindings.getBody()).singleElement().satisfies(record -> assertThat(record)
                .containsEntry("uid", "2283055618")
                .containsEntry("memberName", "流水玩家")
                .containsEntry("status", "READY")
                .containsEntry("durationMinutes", 45));
        assertThat(number(bindings.getBody().get(0).get("memberId"))).isEqualTo(memberId);

        ResponseEntity<List<Map<String, Object>>> charges = http.exchange(
                "/api/records/wristband-charges",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(charges.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(charges.getBody()).singleElement().satisfies(record -> assertThat(record)
                .containsEntry("uid", "2283055618")
                .containsEntry("durationMinutes", 45)
                .containsEntry("unitPriceCents", 100)
                .containsEntry("amountCents", 4500));
    }

    @Test
    void firstValidGameSwipeActivatesReadyBindingAndRepeatedSwipeDoesNotResetTime() {
        long memberId = createReadyWristband("13700137000", "计时玩家", 60);

        ResponseEntity<Map<String, Object>> activated = post("/api/game-access/activate", Map.of(
                "uid", "2283055618",
                "deviceId", "game-01",
                "roomId", "room-01"));

        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> member = map(activated.getBody().get("member"));
        Map<String, Object> access = map(activated.getBody().get("access"));
        assertThat(number(member.get("id"))).isEqualTo(memberId);
        assertThat(member).containsEntry("name", "计时玩家");
        assertThat(access).containsEntry("uid", "2283055618");
        assertThat(access).containsEntry("status", "ACTIVE");
        assertThat(access).containsEntry("durationMinutes", 60);
        assertThat(access).containsEntry("startedAt", "2026-08-09T02:00:00Z");
        assertThat(access).containsEntry("expiresAt", "2026-08-09T03:00:00Z");
        assertThat(number(access.get("remainingSeconds"))).isEqualTo(3600L);

        clock.advance(Duration.ofMinutes(10));
        ResponseEntity<Map<String, Object>> repeated = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        Map<String, Object> repeatedAccess = map(repeated.getBody().get("access"));
        assertThat(repeatedAccess).containsEntry("startedAt", "2026-08-09T02:00:00Z");
        assertThat(repeatedAccess).containsEntry("expiresAt", "2026-08-09T03:00:00Z");
        assertThat(number(repeatedAccess.get("remainingSeconds"))).isEqualTo(3000L);
        assertThat(jdbc.queryForObject(
                "SELECT started_at FROM wristband_bindings WHERE status='ACTIVE'",
                String.class)).isEqualTo("2026-08-09T02:00:00Z");
    }

    @Test
    void deniesUnknownUnboundFrozenAndExpiredWristbandsWithStableCodes() {
        ResponseEntity<Map<String, Object>> unknown = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        assertError(unknown, HttpStatus.NOT_FOUND, "WRISTBAND_NOT_FOUND");

        post("/api/wristbands/charge", Map.of("uid", "2283055618", "durationMinutes", 30));
        ResponseEntity<Map<String, Object>> unbound = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        assertError(unbound, HttpStatus.CONFLICT, "WRISTBAND_NOT_BOUND");

        post("/api/wristbands/clear", Map.of("uid", "2283055618"));
        long frozenMember = number(post("/api/members", Map.of(
                "phone", "13600136000", "name", "冻结玩家")).getBody().get("id"));
        post("/api/wristbands/charge", Map.of("uid", "2283055618", "durationMinutes", 30));
        post("/api/wristbands/bind", Map.of("uid", "2283055618", "memberId", frozenMember));
        jdbc.update("UPDATE members SET status='FROZEN' WHERE id=?", frozenMember);
        ResponseEntity<Map<String, Object>> frozen = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        assertError(frozen, HttpStatus.CONFLICT, "MEMBER_FROZEN");

        jdbc.update("UPDATE members SET status='ACTIVE' WHERE id=?", frozenMember);
        ResponseEntity<Map<String, Object>> active = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofMinutes(31));
        ResponseEntity<Map<String, Object>> expired = post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01"));
        assertError(expired, HttpStatus.CONFLICT, "WRISTBAND_EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wristbands WHERE card_uid='2283055618'",
                String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wristband_bindings WHERE wristband_id=(SELECT id FROM wristbands WHERE card_uid='2283055618') ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("EXPIRED");
    }

    @Test
    void wristbandLookupReturnsServerDerivedBalanceForReadyAndActive() {
        createReadyWristband("13500135000", "余额玩家", 45);

        ResponseEntity<Map<String, Object>> ready = get("/api/wristbands/2283055618");
        assertThat(ready.getBody()).containsEntry("status", "READY");
        assertThat(number(ready.getBody().get("remainingSeconds"))).isEqualTo(2700L);
        assertThat(ready.getBody().get("startedAt")).isNull();
        assertThat(ready.getBody().get("expiresAt")).isNull();

        post("/api/game-access/activate", Map.of("uid", "2283055618", "deviceId", "game-01"));
        clock.advance(Duration.ofSeconds(30));
        ResponseEntity<Map<String, Object>> active = get("/api/wristbands/2283055618");
        assertThat(active.getBody()).containsEntry("status", "ACTIVE");
        assertThat(active.getBody()).containsEntry("startedAt", "2026-08-09T02:00:00Z");
        assertThat(active.getBody()).containsEntry("expiresAt", "2026-08-09T02:45:00Z");
        assertThat(number(active.getBody().get("remainingSeconds"))).isEqualTo(2670L);
    }

    @Test
    void createsRunningPlayWithAuthoritativeMemberBindingAndGameContext() {
        long memberId = createReadyWristband("13400134000", "记录玩家", 60);
        Map<String, Object> access = map(post(
                "/api/game-access/activate",
                Map.of("uid", "2283055618", "deviceId", "game-01", "roomId", "room-a"))
                .getBody().get("access"));

        ResponseEntity<Map<String, Object>> started = startPlay("session-001", "game-memory", "记忆灯阵");

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).containsEntry("status", "RUNNING");
        assertThat(started.getBody()).containsEntry("uid", "2283055618");
        assertThat(started.getBody()).containsEntry("deviceId", "game-01");
        assertThat(started.getBody()).containsEntry("roomId", "room-a");
        assertThat(started.getBody()).containsEntry("gameId", "game-memory");
        assertThat(started.getBody()).containsEntry("gameName", "记忆灯阵");
        assertThat(number(started.getBody().get("memberId"))).isEqualTo(memberId);
        assertThat(number(started.getBody().get("bindingId"))).isEqualTo(number(access.get("bindingId")));
        assertThat(started.getBody()).containsEntry("startedAt", "2026-08-09T02:00:00Z");
    }

    @Test
    void playStartIsIdempotentAndRejectsAnotherRunningGameOnSameBinding() {
        createReadyWristband("13300133000", "幂等玩家", 60);
        post("/api/game-access/activate", Map.of("uid", "2283055618", "deviceId", "game-01"));

        ResponseEntity<Map<String, Object>> first = startPlay("session-001", "game-a", "游戏 A");
        ResponseEntity<Map<String, Object>> repeated = startPlay("session-001", "game-a", "游戏 A");
        assertThat(number(repeated.getBody().get("id"))).isEqualTo(number(first.getBody().get("id")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records", Integer.class)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> concurrent = startPlay("session-002", "game-b", "游戏 B");
        assertError(concurrent, HttpStatus.CONFLICT, "WRISTBAND_IN_USE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records", Integer.class)).isEqualTo(1);
    }

    @Test
    void settlesNaturalAndAbortedResultsIdempotentlyWithoutConsumingAccessWindow() {
        createReadyWristband("13200132000", "结算玩家", 60);
        post("/api/game-access/activate", Map.of("uid", "2283055618", "deviceId", "game-01"));

        ResponseEntity<Map<String, Object>> success = startPlay("session-success", "game-a", "游戏 A");
        ResponseEntity<Map<String, Object>> successResult = put(
                "/api/game-plays/" + number(success.getBody().get("id")) + "/result",
                Map.of("success", true, "terminationReason", "NATURAL_COMPLETION", "rawScore", 88,
                        "pointsAwarded", 12, "resultPayload", Map.of("level", 3)));
        assertThat(successResult.getBody()).containsEntry("status", "COMPLETED");
        assertThat(successResult.getBody()).containsEntry("success", true);
        assertThat(successResult.getBody()).containsEntry("rawScore", 88);
        assertThat(successResult.getBody()).containsEntry("pointsAwarded", 88);
        assertThat(successResult.getBody()).containsEntry("scoringPolicy", "raw-score-v1");
        ResponseEntity<List<Map<String, Object>>> playList = http.exchange(
                "/api/game-plays", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(playList.getBody()).anySatisfy(play -> assertThat(play)
                .containsEntry("rawScore", 88)
                .containsEntry("pointsAwarded", 88)
                .containsEntry("scoringPolicy", "raw-score-v1")
                .containsEntry("terminationReason", "NATURAL_COMPLETION"));

        ResponseEntity<Map<String, Object>> duplicateResult = put(
                "/api/game-plays/" + number(success.getBody().get("id")) + "/result",
                Map.of("success", false, "terminationReason", "NATURAL_FAILURE", "rawScore", 999,
                        "pointsAwarded", 999));
        assertThat(duplicateResult.getBody()).containsEntry("endedAt", successResult.getBody().get("endedAt"));
        assertThat(duplicateResult.getBody()).containsEntry("terminationReason", "NATURAL_COMPLETION");
        assertThat(duplicateResult.getBody()).containsEntry("rawScore", 88);
        assertThat(duplicateResult.getBody()).containsEntry("pointsAwarded", 88);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id='session-success'",
                Integer.class)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> failure = startPlay("session-failure", "game-b", "游戏 B");
        ResponseEntity<Map<String, Object>> failureResult = put(
                "/api/game-plays/" + number(failure.getBody().get("id")) + "/result",
                Map.of("success", false, "terminationReason", "NATURAL_FAILURE", "rawScore", 20,
                        "pointsAwarded", 2));
        assertThat(failureResult.getBody()).containsEntry("status", "COMPLETED");
        assertThat(failureResult.getBody()).containsEntry("success", false);
        assertThat(failureResult.getBody()).containsEntry("pointsAwarded", 20);
        assertThat(failureResult.getBody()).containsEntry("scoringPolicy", "raw-score-v1");

        for (String reason : List.of("MANUAL_STOP", "STARTUP_ABORT", "RUNTIME_ERROR")) {
            ResponseEntity<Map<String, Object>> play = startPlay("session-" + reason, "game-c", "游戏 C");
            ResponseEntity<Map<String, Object>> aborted = put(
                    "/api/game-plays/" + number(play.getBody().get("id")) + "/result",
                    Map.of("success", false, "terminationReason", reason, "pointsAwarded", 99));
            assertThat(aborted.getBody()).containsEntry("status", "ABORTED");
            assertThat(aborted.getBody()).containsEntry("pointsAwarded", 0);
            assertThat(aborted.getBody()).containsEntry("scoringPolicy", "raw-score-v1");
        }

        clock.advance(Duration.ofMinutes(20));
        ResponseEntity<Map<String, Object>> access = get("/api/wristbands/2283055618");
        assertThat(access.getBody()).containsEntry("status", "ACTIVE");
        assertThat(number(access.getBody().get("remainingSeconds"))).isEqualTo(2400L);
        assertThat(startPlay("session-next", "game-next", "下一局").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void playerInfoAggregatesProfilePointsRankWristbandBalanceAndRecentPlays() {
        ResponseEntity<Map<String, Object>> unknown = get("/api/player-info?phone=13100131099");
        assertError(unknown, HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND");

        long memberId = number(post("/api/members", Map.of(
                "phone", "13100131000",
                "name", "查询玩家",
                "avatarId", "avatar-07",
                "birthday", "1999-09-09",
                "gender", "female",
                "createdBy", "kiosk")).getBody().get("id"));
        chargeAndBind("2283055618", memberId, 60);
        post("/api/game-access/activate", Map.of("uid", "2283055618", "deviceId", "game-01"));
        ResponseEntity<Map<String, Object>> firstPlay = startPlay(
                "2283055618", "session-player-1", "game-a", "游戏 A");
        clock.advance(Duration.ofMinutes(5));
        put("/api/game-plays/" + number(firstPlay.getBody().get("id")) + "/result", Map.of(
                "success", true,
                "terminationReason", "NATURAL_COMPLETION",
                "rawScore", 99,
                "pointsAwarded", 12));

        long leaderId = number(post("/api/members", Map.of(
                "phone", "13100131001", "name", "排行玩家")).getBody().get("id"));
        chargeAndBind("2283055619", leaderId, 60);
        post("/api/game-access/activate", Map.of("uid", "2283055619", "deviceId", "game-02"));
        ResponseEntity<Map<String, Object>> leaderPlay = startPlay(
                "2283055619", "session-player-2", "game-b", "游戏 B");
        put("/api/game-plays/" + number(leaderPlay.getBody().get("id")) + "/result", Map.of(
                "success", true,
                "terminationReason", "NATURAL_COMPLETION",
                "rawScore", 130,
                "pointsAwarded", 30));

        ResponseEntity<Map<String, Object>> response = get("/api/player-info?phone=13100131000");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> profile = map(response.getBody().get("profile"));
        assertThat(number(profile.get("id"))).isEqualTo(memberId);
        assertThat(profile).containsEntry("phone", "13100131000");
        assertThat(profile).containsEntry("name", "查询玩家");
        assertThat(profile).containsEntry("avatarId", "avatar-07");
        assertThat(profile).containsEntry("birthday", "1999-09-09");
        assertThat(profile).containsEntry("gender", "female");
        assertThat(profile).containsEntry("createdBy", "kiosk");

        Map<String, Object> points = map(response.getBody().get("points"));
        assertThat(number(points.get("total"))).isEqualTo(99L);
        assertThat(number(points.get("rank"))).isEqualTo(2L);

        List<Map<String, Object>> wristbands = maps(response.getBody().get("wristbands"));
        assertThat(wristbands).hasSize(1);
        assertThat(wristbands.get(0)).containsEntry("uid", "2283055618");
        assertThat(wristbands.get(0)).containsEntry("status", "ACTIVE");
        assertThat(number(wristbands.get(0).get("remainingSeconds"))).isEqualTo(3300L);

        List<Map<String, Object>> recentPlays = maps(response.getBody().get("recentPlays"));
        assertThat(recentPlays).hasSize(1);
        assertThat(recentPlays.get(0)).containsEntry("gameName", "游戏 A");
        assertThat(recentPlays.get(0)).containsEntry("rawScore", 99);
        assertThat(recentPlays.get(0)).containsEntry("pointsAwarded", 99);
        assertThat(recentPlays.get(0)).containsEntry("scoringPolicy", "raw-score-v1");
        assertThat(recentPlays.get(0)).containsEntry("status", "COMPLETED");
    }

    @Test
    void isolatedCoreSmokeRunsFromRegistrationThroughGameSettlementAndPlayerInfo() {
        long memberId = number(post("/api/members", Map.of(
                "phone", "13000130000", "name", "烟测玩家", "createdBy", "e2e"))
                .getBody().get("id"));
        assertThat(post("/api/wristbands/charge", Map.of(
                "uid", "2283055618", "durationMinutes", 90)).getBody())
                .containsEntry("status", "CHARGED");
        assertThat(post("/api/wristbands/bind", Map.of(
                "uid", "2283055618", "memberId", memberId)).getBody())
                .containsEntry("status", "READY");
        assertThat(map(post("/api/game-access/activate", Map.of(
                "uid", "2283055618", "deviceId", "smoke-game", "roomId", "smoke-room"))
                .getBody().get("access"))).containsEntry("status", "ACTIVE");

        ResponseEntity<Map<String, Object>> play = post("/api/game-plays/start", Map.of(
                "uid", "2283055618",
                "deviceId", "smoke-game",
                "roomId", "smoke-room",
                "externalSessionId", "smoke-session-001",
                "gameId", "smoke-light-game",
                "gameName", "核心烟测游戏"));
        assertThat(play.getBody()).containsEntry("status", "RUNNING");
        assertThat(put("/api/game-plays/" + number(play.getBody().get("id")) + "/result", Map.of(
                "success", true,
                "terminationReason", "NATURAL_COMPLETION",
                "rawScore", 100,
                "pointsAwarded", 25)).getBody()).containsEntry("status", "COMPLETED");

        Map<String, Object> info = get("/api/player-info?phone=13000130000").getBody();
        assertThat(map(info.get("profile"))).containsEntry("name", "烟测玩家");
        assertThat(map(info.get("points"))).containsEntry("total", 100);
        assertThat(maps(info.get("recentPlays"))).hasSize(1);
        assertThat(maps(info.get("wristbands")).get(0)).containsEntry("uid", "2283055618");

        Map<String, Object> leaderboard = get("/api/leaderboard?period=year").getBody();
        Map<String, Object> rankedMember = maps(leaderboard.get("entries")).stream()
                .filter(entry -> number(entry.get("memberId")) == memberId)
                .findFirst().orElseThrow();
        assertThat(number(rankedMember.get("points"))).isEqualTo(100L);
        assertThat(number(rankedMember.get("rank"))).isEqualTo(1L);
    }

    @Test
    void platformScoringHandlesMissingAndNegativeScoresAndSharedRanks() {
        long firstMember = number(post("/api/members", Map.of(
                "phone", "13000130010", "name", "积分并列甲")).getBody().get("id"));
        chargeAndBind("2283055620", firstMember, 60);
        post("/api/game-access/activate", Map.of("uid", "2283055620", "deviceId", "game-01"));
        ResponseEntity<Map<String, Object>> firstPlay = startPlay(
                "2283055620", "rank-session-1", "game-rank", "排名游戏");
        put("/api/game-plays/" + number(firstPlay.getBody().get("id")) + "/result", Map.of(
                "success", true, "terminationReason", "NATURAL_COMPLETION",
                "rawScore", 50, "pointsAwarded", 5000));

        long secondMember = number(post("/api/members", Map.of(
                "phone", "13000130011", "name", "积分并列乙")).getBody().get("id"));
        chargeAndBind("2283055621", secondMember, 60);
        post("/api/game-access/activate", Map.of("uid", "2283055621", "deviceId", "game-02"));
        ResponseEntity<Map<String, Object>> secondPlay = startPlay(
                "2283055621", "rank-session-2", "game-rank", "排名游戏");
        put("/api/game-plays/" + number(secondPlay.getBody().get("id")) + "/result", Map.of(
                "success", false, "terminationReason", "NATURAL_FAILURE",
                "rawScore", 50, "pointsAwarded", 0));

        long thirdMember = number(post("/api/members", Map.of(
                "phone", "13000130012", "name", "积分第三名")).getBody().get("id"));
        chargeAndBind("2283055622", thirdMember, 60);
        post("/api/game-access/activate", Map.of("uid", "2283055622", "deviceId", "game-03"));
        ResponseEntity<Map<String, Object>> negativePlay = startPlay(
                "2283055622", "rank-session-3", "game-rank", "排名游戏");
        Map<String, Object> missingScore = new java.util.LinkedHashMap<>();
        missingScore.put("success", true);
        missingScore.put("terminationReason", "NATURAL_COMPLETION");
        missingScore.put("rawScore", null);
        missingScore.put("pointsAwarded", 999);
        ResponseEntity<Map<String, Object>> missingResult = put(
                "/api/game-plays/" + number(negativePlay.getBody().get("id")) + "/result", missingScore);
        assertThat(missingResult.getBody()).containsEntry("pointsAwarded", 0);
        assertThat(missingResult.getBody()).containsEntry("scoringPolicy", "raw-score-v1");

        ResponseEntity<Map<String, Object>> belowZeroPlay = startPlay(
                "2283055622", "rank-session-4", "game-rank", "鎺掑悕娓告垙");
        ResponseEntity<Map<String, Object>> belowZeroResult = put(
                "/api/game-plays/" + number(belowZeroPlay.getBody().get("id")) + "/result", Map.of(
                        "success", false,
                        "terminationReason", "NATURAL_FAILURE",
                        "rawScore", -10,
                        "pointsAwarded", 888));
        assertThat(belowZeroResult.getBody()).containsEntry("pointsAwarded", 0);
        assertThat(belowZeroResult.getBody()).containsEntry("scoringPolicy", "raw-score-v1");

        assertThat(map(get("/api/player-info?phone=13000130010").getBody().get("points")))
                .containsEntry("total", 50).containsEntry("rank", 1);
        assertThat(map(get("/api/player-info?phone=13000130011").getBody().get("points")))
                .containsEntry("total", 50).containsEntry("rank", 1);
        assertThat(map(get("/api/player-info?phone=13000130012").getBody().get("points")))
                .containsEntry("total", 0).containsEntry("rank", 3);

        ResponseEntity<List<Map<String, Object>>> members = http.exchange(
                "/api/members", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        Map<String, Object> firstProjection = members.getBody().stream()
                .filter(member -> "13000130010".equals(member.get("phone"))).findFirst().orElseThrow();
        assertThat(firstProjection).containsEntry("pointsTotal", 50).containsEntry("rank", 1);
    }

    @Test
    void multiplayerBatchStartIsAtomicIdempotentAndSettlesEveryMember() {
        long firstMember = createActiveWristband(
                "2283055701", "13000130101", "多人测试玩家甲");
        long secondMember = createActiveWristband(
                "2283055702", "13000130102", "多人测试玩家乙");

        Map<String, Object> request = Map.of(
                "uids", List.of("2283055701", "2283055702"),
                "deviceId", "multiplayer-device",
                "roomId", "multiplayer-room",
                "externalSessionId", "multiplayer-session-001",
                "gameId", "simple",
                "gameName", "多人核心测试");
        ResponseEntity<List<Map<String, Object>>> started = postList(
                "/api/game-plays/start-batch", request);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).hasSize(2);
        assertThat(started.getBody()).extracting(play -> play.get("uid"))
                .containsExactly("2283055701", "2283055702");
        assertThat(started.getBody()).extracting(play -> number(play.get("participantIndex")))
                .containsExactly(0L, 1L);

        ResponseEntity<List<Map<String, Object>>> repeated = postList(
                "/api/game-plays/start-batch", request);
        assertThat(repeated.getBody()).extracting(play -> number(play.get("id")))
                .containsExactlyElementsOf(started.getBody().stream()
                        .map(play -> number(play.get("id"))).toList());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "multiplayer-session-001")).isEqualTo(2);

        for (Map<String, Object> play : started.getBody()) {
            Map<String, Object> result = Map.of(
                    "success", true,
                    "terminationReason", "NATURAL_COMPLETION",
                    "rawScore", 88,
                    "pointsAwarded", 999,
                    "resultPayload", Map.of("shared", true));
            ResponseEntity<Map<String, Object>> settled = put(
                    "/api/game-plays/" + number(play.get("id")) + "/result", result);
            assertThat(settled.getBody())
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("rawScore", 88)
                    .containsEntry("pointsAwarded", 88);
            assertThat(put(
                    "/api/game-plays/" + number(play.get("id")) + "/result", result).getBody())
                    .containsEntry("pointsAwarded", 88);
        }

        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(points_awarded), 0) FROM game_play_records WHERE member_id=?",
                Long.class, firstMember)).isEqualTo(88L);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(points_awarded), 0) FROM game_play_records WHERE member_id=?",
                Long.class, secondMember)).isEqualTo(88L);
        assertThat(maps(get("/api/player-info?phone=13000130101").getBody().get("recentPlays")))
                .hasSize(1);
        assertThat(maps(get("/api/player-info?phone=13000130102").getBody().get("recentPlays")))
                .hasSize(1);
    }

    @Test
    void multiplayerBatchRejectsIneligibleOrDuplicateParticipantsWithoutPartialRecords() {
        long firstMember = createActiveWristband(
                "2283055711", "13000130111", "原子测试玩家甲");
        long secondMember = number(post("/api/members", Map.of(
                "phone", "13000130112", "name", "原子测试玩家乙")).getBody().get("id"));
        chargeAndBind("2283055712", secondMember, 60);

        ResponseEntity<Map<String, Object>> ineligible = post(
                "/api/game-plays/start-batch", Map.of(
                        "uids", List.of("2283055711", "2283055712"),
                        "deviceId", "atomic-device",
                        "externalSessionId", "atomic-session-001",
                        "gameId", "normal",
                        "gameName", "原子失败测试"));
        assertError(ineligible, HttpStatus.CONFLICT, "WRISTBAND_NOT_ACTIVATED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "atomic-session-001")).isZero();

        ResponseEntity<Map<String, Object>> duplicateUid = post(
                "/api/game-plays/start-batch", Map.of(
                        "uids", List.of("2283055711", "2283055711"),
                        "deviceId", "atomic-device",
                        "externalSessionId", "atomic-session-002",
                        "gameId", "normal",
                        "gameName", "重复手环测试"));
        assertError(duplicateUid, HttpStatus.CONFLICT, "DUPLICATE_WRISTBAND");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "atomic-session-002")).isZero();

        chargeAndBind("2283055713", firstMember, 60);
        post("/api/game-access/activate", Map.of(
                "uid", "2283055713", "deviceId", "atomic-device"));
        ResponseEntity<Map<String, Object>> duplicateMember = post(
                "/api/game-plays/start-batch", Map.of(
                        "uids", List.of("2283055711", "2283055713"),
                        "deviceId", "atomic-device",
                        "externalSessionId", "atomic-session-003",
                        "gameId", "normal",
                        "gameName", "重复会员测试"));
        assertError(duplicateMember, HttpStatus.CONFLICT, "DUPLICATE_MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "atomic-session-003")).isZero();
    }

    @Test
    void multiplayerBatchRejectsConflictingParticipantOrderAndConcurrentBindingUse() {
        createActiveWristband("2283055721", "13000130121", "幂等测试玩家甲");
        createActiveWristband("2283055722", "13000130122", "幂等测试玩家乙");
        Map<String, Object> first = Map.of(
                "uids", List.of("2283055721", "2283055722"),
                "deviceId", "idempotent-device",
                "externalSessionId", "idempotent-session-001",
                "gameId", "diffcult",
                "gameName", "幂等测试");
        assertThat(postList("/api/game-plays/start-batch", first).getBody()).hasSize(2);

        ResponseEntity<Map<String, Object>> reordered = post(
                "/api/game-plays/start-batch", Map.of(
                        "uids", List.of("2283055722", "2283055721"),
                        "deviceId", "idempotent-device",
                        "externalSessionId", "idempotent-session-001",
                        "gameId", "diffcult",
                        "gameName", "幂等测试"));
        assertError(reordered, HttpStatus.CONFLICT, "GAME_PLAY_PARTICIPANTS_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "idempotent-session-001")).isEqualTo(2);

        ResponseEntity<Map<String, Object>> concurrent = post(
                "/api/game-plays/start-batch", Map.of(
                        "uids", List.of("2283055721"),
                        "deviceId", "other-device",
                        "externalSessionId", "idempotent-session-002",
                        "gameId", "simple",
                        "gameName", "占用测试"));
        assertError(concurrent, HttpStatus.CONFLICT, "WRISTBAND_IN_USE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_play_records WHERE external_session_id=?",
                Integer.class, "idempotent-session-002")).isZero();
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return http.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> postList(String path, Object body) {
        return http.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(
                path,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> put(String path, Object body) {
        return http.exchange(
                path,
                HttpMethod.PUT,
                new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> startPlay(
            String externalSessionId,
            String gameId,
            String gameName) {
        return startPlay("2283055618", externalSessionId, gameId, gameName);
    }

    private ResponseEntity<Map<String, Object>> startPlay(
            String uid,
            String externalSessionId,
            String gameId,
            String gameName) {
        return post("/api/game-plays/start", Map.of(
                "uid", uid,
                "deviceId", "game-01",
                "roomId", "room-a",
                "externalSessionId", externalSessionId,
                "gameId", gameId,
                "gameName", gameName));
    }

    private long createReadyWristband(String phone, String name, int durationMinutes) {
        long memberId = number(post("/api/members", Map.of(
                "phone", phone,
                "name", name)).getBody().get("id"));
        chargeAndBind("2283055618", memberId, durationMinutes);
        return memberId;
    }

    private void chargeAndBind(String uid, long memberId, int durationMinutes) {
        post("/api/wristbands/charge", Map.of("uid", uid, "durationMinutes", durationMinutes));
        post("/api/wristbands/bind", Map.of("uid", uid, "memberId", memberId));
    }

    private long createActiveWristband(String uid, String phone, String name) {
        long memberId = number(post("/api/members", Map.of(
                "phone", phone, "name", name, "createdBy", "multiplayer-test"))
                .getBody().get("id"));
        chargeAndBind(uid, memberId, 60);
        assertThat(post("/api/game-access/activate", Map.of(
                "uid", uid, "deviceId", "multiplayer-test-device")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return memberId;
    }

    private static void assertError(
            ResponseEntity<Map<String, Object>> response,
            HttpStatus status,
            String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code);
        assertThat(response.getBody().get("message")).isInstanceOf(String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
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
            Path path = Files.createTempFile("ledgame-platform-test-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create temporary SQLite path", exception);
        }
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-09T02:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
