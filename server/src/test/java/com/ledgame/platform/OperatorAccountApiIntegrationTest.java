package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorAccountApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath());
        registry.add("ledgame.operator-accounts.factory.username", () -> "factory-test");
        registry.add("ledgame.operator-accounts.factory.password", () -> "test-password");
        registry.add("ledgame.operator-accounts.factory.display-name", () -> "测试出厂管理员");
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    private long factoryId;

    @BeforeEach
    void resetAccounts() {
        jdbc.update("DELETE FROM operator_action_logs");
        jdbc.update("DELETE FROM game_play_records");
        jdbc.update("DELETE FROM wristband_bindings");
        jdbc.update("DELETE FROM wristband_charge_records");
        jdbc.update("DELETE FROM wristbands");
        jdbc.update("DELETE FROM members");
        jdbc.update("DELETE FROM room_settings");
        jdbc.update("UPDATE store_feature_settings SET child_mode=0");
        jdbc.update("DELETE FROM operator_accounts");
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO operator_accounts(
                username, display_name, password_hash, account_type,
                enabled, created_at, updated_at)
            VALUES ('factory-test', '测试出厂管理员', ?, 'FACTORY_ADMIN', 1, ?, ?)
            """, passwordEncoder.encode("test-password"), now, now);
        factoryId = jdbc.queryForObject(
                "SELECT id FROM operator_accounts WHERE username='factory-test'", Long.class);
    }

    @AfterAll
    void deleteDatabase() throws IOException {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
        Files.deleteIfExists(DATABASE_PATH);
    }

    @Test
    void logsInWithConfiguredFactoryCredentialsAndReturnsOnlyPublicProfile() {
        ResponseEntity<Map<String, Object>> response = post("/api/operator-auth/login", Map.of(
                "username", "FACTORY-TEST",
                "password", "test-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("username", "factory-test")
                .containsEntry("displayName", "测试出厂管理员")
                .containsEntry("accountType", "FACTORY_ADMIN")
                .doesNotContainKeys("password", "passwordHash", "enabled");
        assertThat(number(response.getBody().get("id"))).isEqualTo(factoryId);
    }

    @Test
    void loginUsesOneStableFailureForUnknownWrongPasswordAndDisabledAccounts() {
        createOperator("disabled", "已停用", "disabled-password", false);

        for (Map<String, String> request : List.of(
                Map.of("username", "missing", "password", "whatever"),
                Map.of("username", "factory-test", "password", "wrong-password"),
                Map.of("username", "disabled", "password", "disabled-password"))) {
            ResponseEntity<Map<String, Object>> response = post("/api/operator-auth/login", request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody())
                    .containsEntry("code", "OPERATOR_LOGIN_FAILED")
                    .doesNotContainKeys("password", "passwordHash");
        }
    }

    @Test
    void factoryCanListCreateEditResetPasswordAndDisableOperatorWithoutPasswordLeaks() {
        ResponseEntity<Map<String, Object>> created = post("/api/operator-accounts", Map.of(
                "username", "front-desk",
                "displayName", "前台小王",
                "password", "initial-password"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody())
                .containsEntry("username", "front-desk")
                .containsEntry("displayName", "前台小王")
                .containsEntry("accountType", "OPERATOR")
                .containsEntry("enabled", true)
                .doesNotContainKeys("password", "passwordHash");
        long operatorId = number(created.getBody().get("id"));

        ResponseEntity<List<Map<String, Object>>> listed = http.exchange(
                "/api/operator-accounts", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(2);
        assertThat(listed.getBody()).allSatisfy(account ->
                assertThat(account).doesNotContainKeys("password", "passwordHash"));

        ResponseEntity<Map<String, Object>> edited = put(
                "/api/operator-accounts/" + operatorId,
                Map.of("username", "counter", "displayName", "收银台"));
        assertThat(edited.getBody())
                .containsEntry("username", "counter")
                .containsEntry("displayName", "收银台");

        assertThat(put("/api/operator-accounts/" + operatorId + "/password",
                Map.of("password", "changed-password")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/operator-auth/login", Map.of(
                "username", "counter", "password", "changed-password")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> disabled = put(
                "/api/operator-accounts/" + operatorId + "/enabled", Map.of("enabled", false));
        assertThat(disabled.getBody()).containsEntry("enabled", false);
        assertThat(post("/api/operator-auth/login", Map.of(
                "username", "counter", "password", "changed-password")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsCaseInsensitiveUsernameConflictsAndProtectsFactoryAdmin() {
        long operatorId = createOperator("front-desk", "前台", "initial-password", true);

        ResponseEntity<Map<String, Object>> duplicate = post("/api/operator-accounts", Map.of(
                "username", "FRONT-DESK",
                "displayName", "重名",
                "password", "another-password"));
        assertError(duplicate, HttpStatus.CONFLICT, "OPERATOR_USERNAME_CONFLICT");

        ResponseEntity<Map<String, Object>> renamedDuplicate = put(
                "/api/operator-accounts/" + operatorId,
                Map.of("username", "FACTORY-TEST", "displayName", "前台"));
        assertError(renamedDuplicate, HttpStatus.CONFLICT, "OPERATOR_USERNAME_CONFLICT");

        ResponseEntity<Map<String, Object>> disabledFactory = put(
                "/api/operator-accounts/" + factoryId + "/enabled", Map.of("enabled", false));
        assertError(disabledFactory, HttpStatus.CONFLICT, "FACTORY_ADMIN_PROTECTED");
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM operator_accounts WHERE id=?", Integer.class, factoryId)).isEqualTo(1);
    }

    @Test
    void resolvesOperatorContextAndLogsOnlySuccessfulWritesWithNameSnapshots() {
        ResponseEntity<Map<String, Object>> created = postAsOperator("/api/members", Map.of(
                "phone", "13800138000", "name", "留痕测试会员"), factoryId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT created_by FROM members WHERE phone='13800138000'", String.class))
                .isEqualTo("operator:factory-test");
        assertThat(jdbc.queryForMap("SELECT * FROM operator_action_logs"))
                .containsEntry("operator_id", (int) factoryId)
                .containsEntry("operator_username", "factory-test")
                .containsEntry("operator_display_name", "测试出厂管理员")
                .containsEntry("action", "MEMBER_CREATED")
                .containsEntry("target_type", "MEMBER");

        jdbc.update("UPDATE operator_accounts SET display_name='已改名管理员' WHERE id=?", factoryId);
        assertThat(jdbc.queryForObject(
                "SELECT operator_display_name FROM operator_action_logs", String.class))
                .isEqualTo("测试出厂管理员");

        ResponseEntity<Map<String, Object>> failed = postAsOperator("/api/members", Map.of(
                "phone", "13800138000", "name", "重复会员"), factoryId);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_action_logs", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownOperatorHeaderBeforeMutatingBusinessData() {
        ResponseEntity<Map<String, Object>> response = postAsOperator("/api/members", Map.of(
                "phone", "13900139000", "name", "不应写入"), 999999L);
        assertError(response, HttpStatus.BAD_REQUEST, "OPERATOR_CONTEXT_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_action_logs", Integer.class)).isZero();
    }

    @Test
    void logsAccountMemberWristbandAndRoomManagementActions() {
        long memberId = number(postAsOperator("/api/members", Map.of(
                "phone", "13700137000", "name", "综合留痕会员"), factoryId).getBody().get("id"));
        long operatorId = number(postAsOperator("/api/operator-accounts", Map.of(
                "username", "audit-user", "displayName", "留痕操作员", "password", "123456"), factoryId)
                .getBody().get("id"));
        putAsOperator("/api/operator-accounts/" + operatorId,
                Map.of("username", "audit-user", "displayName", "留痕改名"), factoryId);
        putAsOperator("/api/operator-accounts/" + operatorId + "/password",
                Map.of("password", "654321"), factoryId);
        putAsOperator("/api/operator-accounts/" + operatorId + "/enabled",
                Map.of("enabled", false), factoryId);

        postAsOperator("/api/wristbands/charge", Map.of("uid", "10001", "durationMinutes", 30), factoryId);
        postAsOperator("/api/wristbands/clear", Map.of("uid", "10001"), factoryId);
        postAsOperator("/api/wristbands/charge", Map.of("uid", "10002", "durationMinutes", 30), factoryId);
        postAsOperator("/api/wristbands/bind", Map.of("uid", "10002", "memberId", memberId), factoryId);
        postAsOperator("/api/wristbands/unbind", Map.of("uid", "10002"), factoryId);
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO wristbands(card_uid, status, duration_minutes, charged_at, created_at, updated_at)
            VALUES ('10003', 'EXPIRED', 30, ?, ?, ?)
            """, now, now, now);
        postAsOperator("/api/wristbands/reclaim", Map.of("uid", "10003"), factoryId);
        putAsOperator("/api/rooms/192.168.1.25", Map.of("roomName", "A区游戏桌"), factoryId);
        putAsOperator("/api/feature-settings/child-mode", Map.of("enabled", true), factoryId);
        deleteAsOperator("/api/members/" + memberId, factoryId);

        assertThat(jdbc.queryForList("SELECT action FROM operator_action_logs").stream()
                .map(row -> String.valueOf(row.get("action"))))
                .contains(
                        "ACCOUNT_CREATED", "ACCOUNT_UPDATED", "ACCOUNT_PASSWORD_RESET",
                        "ACCOUNT_ENABLED_CHANGED", "MEMBER_CREATED", "MEMBER_DELETED",
                        "WRISTBAND_CHARGED", "WRISTBAND_BALANCE_CLEARED", "WRISTBAND_UNBOUND",
                        "WRISTBAND_RECLAIMED", "ROOM_RENAMED", "SYSTEM_SETTINGS_UPDATED");
        assertThat(jdbc.queryForMap("SELECT action, target_id FROM operator_action_logs WHERE action='SYSTEM_SETTINGS_UPDATED'"))
                .containsEntry("target_id", "child-mode");
        assertThat(jdbc.queryForList("SELECT summary_json FROM operator_action_logs").stream()
                .map(row -> String.valueOf(row.get("summary_json"))))
                .allSatisfy(summary -> assertThat(summary).doesNotContain("654321", "123456"));
    }

    private long createOperator(String username, String displayName, String password, boolean enabled) {
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO operator_accounts(
                username, display_name, password_hash, account_type,
                enabled, created_by_operator_id, created_at, updated_at)
            VALUES (?, ?, ?, 'OPERATOR', ?, ?, ?, ?)
            """, username, displayName, passwordEncoder.encode(password), enabled ? 1 : 0,
                factoryId, now, now);
        return jdbc.queryForObject("SELECT id FROM operator_accounts WHERE username=?", Long.class, username);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> put(String path, Object body) {
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postAsOperator(String path, Object body, long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator-Id", String.valueOf(operatorId));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> putAsOperator(String path, Object body, long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator-Id", String.valueOf(operatorId));
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> deleteAsOperator(String path, long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator-Id", String.valueOf(operatorId));
        return http.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private static void assertError(
            ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-operator-api-test-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create temporary SQLite path", exception);
        }
    }
}
