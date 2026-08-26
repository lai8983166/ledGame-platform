package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
class MemberDeletionApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void clearData() {
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
    void softDeletesMemberPreservesHistoryAndAllowsFreshRegistrationWithSamePhone() {
        Map<String, Object> original = createMember("13800138001", "重复注册甲", "member-admin");
        long originalId = number(original.get("id"));
        addCompletedHistory(originalId, "2283055701", 88);

        ResponseEntity<Map<String, Object>> deleted = deleteMember(originalId);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.getBody()).containsEntry("id", (int) originalId).containsEntry("status", "DELETED");
        assertThat(jdbc.queryForMap("SELECT status, deleted_at AS deletedAt FROM members WHERE id=?", originalId))
                .containsEntry("status", "FROZEN");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM members WHERE id=?", Integer.class, originalId)).isEqualTo(1);
        assertThat(getMembers("13800138001")).isEmpty();
        assertThat(playerInfo("13800138001").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> leaderboard = http.exchange("/api/leaderboard?period=year", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        assertThat(leaderboard).isNotNull();
        assertThat(list(leaderboard.get("entries"))).isEmpty();

        addBinding(originalId, "2283055799", "READY");
        ResponseEntity<Map<String, Object>> rejectedAdmission = http.exchange("/api/game-access/activate", HttpMethod.POST,
                json(Map.of("uid", "2283055799", "deviceId", "deleted-member-test", "roomId", "deleted-room")),
                new ParameterizedTypeReference<>() {});
        assertThat(rejectedAdmission.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejectedAdmission.getBody()).containsEntry("code", "MEMBER_FROZEN");

        Map<String, Object> replacement = createMember("13800138001", "重复注册乙", "kiosk");
        long replacementId = number(replacement.get("id"));
        assertThat(replacementId).isNotEqualTo(originalId);
        Map<String, Object> info = playerInfo("13800138001").getBody();
        assertThat(info).isNotNull();
        assertThat(number(map(info.get("profile")).get("id"))).isEqualTo(replacementId);
        assertThat(number(map(info.get("points")).get("total"))).isZero();
        assertThat(list(info.get("recentPlays"))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records WHERE member_id=?", Integer.class, originalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_play_records WHERE member_id=?", Integer.class, replacementId)).isZero();
    }

    @Test
    void rejectsMemberWithReadyOrActiveWristbandUsingStableCode() {
        for (String status : List.of("READY", "ACTIVE")) {
            clearData();
            long memberId = number(createMember("1380013800" + (status.equals("READY") ? "2" : "3"), "开放手环" + status, "member-admin").get("id"));
            addBinding(memberId, "22830557" + (status.equals("READY") ? "02" : "03"), status);

            ResponseEntity<Map<String, Object>> response = deleteMember(memberId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("code", "MEMBER_HAS_OPEN_WRISTBAND");
            assertThat(jdbc.queryForObject("SELECT status FROM members WHERE id=?", String.class, memberId)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void rejectsMemberWithRunningGameUsingStableCode() {
        long memberId = number(createMember("13800138004", "运行中玩家", "member-admin").get("id"));
        addRunningHistory(memberId, "2283055704");

        ResponseEntity<Map<String, Object>> response = deleteMember(memberId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "MEMBER_HAS_RUNNING_GAME");
        assertThat(jdbc.queryForObject("SELECT status FROM game_play_records WHERE member_id=?", String.class, memberId)).isEqualTo("RUNNING");
    }

    @Test
    void deletingMissingOrAlreadyDeletedMemberIsStableAndIdempotent() {
        long memberId = number(createMember("13800138005", "重复删除玩家", "member-admin").get("id"));
        assertThat(deleteMember(memberId).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> repeated = deleteMember(memberId);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repeated.getBody()).containsEntry("code", "MEMBER_NOT_FOUND");
        ResponseEntity<Map<String, Object>> missing = deleteMember(999_999L);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "MEMBER_NOT_FOUND");
    }

    private Map<String, Object> createMember(String phone, String name, String createdBy) {
        ResponseEntity<Map<String, Object>> response = http.exchange("/api/members", HttpMethod.POST,
                json(Map.of("phone", phone, "name", name, "createdBy", createdBy)), new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> deleteMember(long id) {
        return http.exchange("/api/members/" + id, HttpMethod.DELETE, null, new ParameterizedTypeReference<>() {});
    }

    private List<Map<String, Object>> getMembers(String phone) {
        return http.exchange("/api/members?phone=" + phone, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}).getBody();
    }

    private ResponseEntity<Map<String, Object>> playerInfo(String phone) {
        return http.exchange("/api/player-info?phone=" + phone, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    private void addCompletedHistory(long memberId, String uid, int points) {
        long bindingId = addBinding(memberId, uid, "RETURNED");
        String endedAt = Instant.now().toString();
        jdbc.update("INSERT INTO game_play_records(member_id,binding_id,wristband_uid,device_id,external_session_id,participant_index,game_id,game_name,status,started_at,ended_at,points_awarded) VALUES (?,?,?,?,?,0,'simple','历史游戏','COMPLETED',?,?,?)",
                memberId, bindingId, uid, "history-device", "history-" + uid, Instant.now().minusSeconds(60).toString(), endedAt, points);
    }

    private void addRunningHistory(long memberId, String uid) {
        long bindingId = addBinding(memberId, uid, "RETURNED");
        jdbc.update("INSERT INTO game_play_records(member_id,binding_id,wristband_uid,device_id,external_session_id,participant_index,game_id,game_name,status,started_at,points_awarded) VALUES (?,?,?,?,?,0,'simple','运行游戏','RUNNING',?,0)",
                memberId, bindingId, uid, "running-device", "running-" + uid, Instant.now().toString());
    }

    private long addBinding(long memberId, String uid, String status) {
        String wristbandStatus = status.equals("RETURNED") ? "EXPIRED" : status;
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO wristbands(card_uid,status,duration_minutes,created_at,updated_at) VALUES (?,?,60,?,?)", uid, wristbandStatus, now, now);
        long wristbandId = jdbc.queryForObject("SELECT id FROM wristbands WHERE card_uid=?", Long.class, uid);
        jdbc.update("INSERT INTO wristband_bindings(wristband_id,member_id,status,duration_minutes,bound_at,started_at,ended_at) VALUES (?,?,?,?,?,?,?)",
                wristbandId, memberId, status, 60, now, status.equals("READY") ? null : now, status.equals("RETURNED") ? now : null);
        return jdbc.queryForObject("SELECT id FROM wristband_bindings WHERE wristband_id=?", Long.class, wristbandId);
    }

    private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-member-delete-test-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
