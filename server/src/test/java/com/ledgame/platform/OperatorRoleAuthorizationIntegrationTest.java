package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ActivatedTestConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorRoleAuthorizationIntegrationTest {
    private static final Path DATABASE = createDatabasePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
        registry.add("ledgame.database-backup.enabled", () -> false);
        registry.add("ledgame.room-connection.enabled", () -> false);
    }

    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    long factoryId;
    long managerId;
    long clerkId;

    @BeforeAll
    void createRoles() {
        factoryId = number(post("/api/operator-auth/login", Map.of(
                "username", "admin", "password", "888888"), null).getBody().get("id"));
        managerId = number(post("/api/operator-accounts", Map.of(
                "username", "manager", "displayName", "测试店长", "password", "123456",
                "accountType", "STORE_MANAGER"), factoryId).getBody().get("id"));
        clerkId = number(post("/api/operator-accounts", Map.of(
                "username", "clerk", "displayName", "测试店员", "password", "123456",
                "accountType", "CLERK"), factoryId).getBody().get("id"));
    }

    @Test
    void enforcesViewExportFeatureAndNoIdentityMatrix() {
        assertThat(get("/api/dashboard/overview", factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/dashboard/overview", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/dashboard/overview", clerkId));
        assertForbidden(get("/api/dashboard/overview", null));

        assertThat(getBytes("/api/exports/members.csv", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getBytes("/api/exports/wristband-charges.csv", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getBytes("/api/exports/game-plays.csv", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(getBytes("/api/exports/members.csv", clerkId));
        assertForbidden(getBytes("/api/exports/members.csv", null));

        assertThat(getList("/api/rooms", factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getList("/api/rooms", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/rooms", clerkId));
        assertThat(getList("/api/game-plays", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/game-plays", clerkId));
        assertThat(getList("/api/records/wristband-charges", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/records/wristband-charges", clerkId));
        assertThat(get("/api/leaderboard", managerId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/leaderboard", clerkId));
        assertThat(get("/api/leaderboard", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/api/database-backup/status", factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(get("/api/database-backup/status", managerId));
        assertForbidden(get("/api/database-backup/status", clerkId));
        assertThat(post("/api/operator-actions/system-settings", Map.of(), factoryId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertForbidden(post("/api/operator-actions/system-settings", Map.of(), managerId));

        assertThat(put("/api/feature-settings/child-mode", Map.of("enabled", true), clerkId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void everyRoleCanPerformWristbandOperationsButMissingIdentityCannot() {
        assertThat(post("/api/wristbands/charge", Map.of(
                "uid", "910000001", "durationMinutes", 10), factoryId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/wristbands/charge", Map.of(
                "uid", "910000002", "durationMinutes", 10), managerId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/wristbands/charge", Map.of(
                "uid", "910000003", "durationMinutes", 10), clerkId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/wristbands/clear", Map.of("uid", "910000003"), clerkId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertForbidden(post("/api/wristbands/charge", Map.of(
                "uid", "910000004", "durationMinutes", 10), null));
    }

    @Test
    void factoryManagesManagersAndClerksWhileManagerOnlyManagesClerks() {
        assertThat(getList("/api/operator-accounts", factoryId).getBody())
                .extracting(row -> row.get("accountType"))
                .contains("FACTORY_ADMIN", "STORE_MANAGER", "CLERK");
        assertThat(getList("/api/operator-accounts", managerId).getBody())
                .allSatisfy(row -> assertThat(row.get("accountType")).isEqualTo("CLERK"));
        assertForbidden(get("/api/operator-accounts", clerkId));

        ResponseEntity<Map<String, Object>> newClerk = post("/api/operator-accounts", Map.of(
                "username", "manager-clerk", "displayName", "店长创建店员", "password", "123456",
                "accountType", "CLERK"), managerId);
        assertThat(newClerk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertForbidden(post("/api/operator-accounts", Map.of(
                "username", "manager-2", "displayName", "越权店长", "password", "123456",
                "accountType", "STORE_MANAGER"), managerId));
        assertThat(delete("/api/operator-accounts/" + number(newClerk.getBody().get("id")), managerId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operator_action_logs
                 WHERE action='ACCOUNT_DELETED' AND operator_id=?
                """, Integer.class, managerId)).isEqualTo(1);
        assertForbidden(delete("/api/operator-accounts/" + managerId, managerId));
    }

    @Test
    void sharedMachineFlowStaysPublicButAdministrativeDeletesStayFactoryOnly() {
        ResponseEntity<Map<String, Object>> member = post("/api/members", Map.of(
                "phone", "13900139000", "name", "机器接口会员"), null);
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.OK);
        long memberId = number(member.getBody().get("id"));
        assertForbidden(delete("/api/members/" + memberId, managerId));
        assertForbidden(delete("/api/members/" + memberId, clerkId));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operator_action_logs
                 WHERE action='MEMBER_DELETED_DENIED' AND operator_id=?
                """, Integer.class, managerId)).isEqualTo(1);
        assertThat(delete("/api/members/" + memberId, factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void disabledDeletedAndUnknownAccountsLoseAuthorizationImmediately() {
        long disabledId = number(post("/api/operator-accounts", Map.of(
                "username", "disabled-role-user", "displayName", "待停用店员", "password", "123456",
                "accountType", "CLERK"), factoryId).getBody().get("id"));
        assertThat(put("/api/operator-accounts/" + disabledId + "/enabled",
                Map.of("enabled", false), factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertSessionInvalid(put("/api/feature-settings/child-mode", Map.of("enabled", false), disabledId));

        long deletedId = number(post("/api/operator-accounts", Map.of(
                "username", "deleted-role-user", "displayName", "待删除店员", "password", "123456",
                "accountType", "CLERK"), factoryId).getBody().get("id"));
        assertThat(delete("/api/operator-accounts/" + deletedId, factoryId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertSessionInvalid(put("/api/feature-settings/child-mode", Map.of("enabled", false), deletedId));
        assertSessionInvalid(put("/api/feature-settings/child-mode", Map.of("enabled", false), 999999L));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, Long operatorId) {
        return exchange(path, HttpMethod.POST, body, operatorId, new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> put(String path, Object body, Long operatorId) {
        return exchange(path, HttpMethod.PUT, body, operatorId, new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> delete(String path, Long operatorId) {
        return exchange(path, HttpMethod.DELETE, null, operatorId, new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> get(String path, Long operatorId) {
        return exchange(path, HttpMethod.GET, null, operatorId, new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, Long operatorId) {
        return exchange(path, HttpMethod.GET, null, operatorId, new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<byte[]> getBytes(String path, Long operatorId) {
        HttpHeaders headers = headers(operatorId);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body, Long operatorId,
            ParameterizedTypeReference<T> type) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(operatorId)), type);
    }

    private static HttpHeaders headers(Long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        if (operatorId != null) headers.set("X-Operator-Id", String.valueOf(operatorId));
        return headers;
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static void assertSessionInvalid(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "OPERATOR_SESSION_INVALID");
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static Path createDatabasePath() {
        try {
            Path path = Files.createTempFile("ledgame-role-matrix-", ".db");
            Files.deleteIfExists(path);
            return path;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) hikari.close();
        Files.deleteIfExists(DATABASE);
    }
}
