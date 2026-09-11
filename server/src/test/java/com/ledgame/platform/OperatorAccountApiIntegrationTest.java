package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ActivatedTestConfiguration.class)
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

    @Autowired
    private OperationalDataExportService exportService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProtectedDataService protectedData;

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
        ResponseEntity<Map<String, Object>> created = postAsOperator("/api/operator-accounts", Map.of(
                "username", "front-desk",
                "displayName", "前台小王",
                "password", "initial-password",
                "accountType", "CLERK"), factoryId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody())
                .containsEntry("username", "front-desk")
                .containsEntry("displayName", "前台小王")
                .containsEntry("accountType", "CLERK")
                .containsEntry("enabled", true)
                .doesNotContainKeys("password", "passwordHash");
        long operatorId = number(created.getBody().get("id"));

        ResponseEntity<List<Map<String, Object>>> listed = http.exchange(
                "/api/operator-accounts", HttpMethod.GET, operatorEntity(factoryId), new ParameterizedTypeReference<>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(2);
        assertThat(listed.getBody()).allSatisfy(account ->
                assertThat(account).doesNotContainKeys("password", "passwordHash"));

        ResponseEntity<Map<String, Object>> edited = putAsOperator(
                "/api/operator-accounts/" + operatorId,
                Map.of("username", "counter", "displayName", "收银台"), factoryId);
        assertThat(edited.getBody())
                .containsEntry("username", "counter")
                .containsEntry("displayName", "收银台");

        assertThat(putAsOperator("/api/operator-accounts/" + operatorId + "/password",
                Map.of("password", "changed-password"), factoryId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/operator-auth/login", Map.of(
                "username", "counter", "password", "changed-password")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> disabled = putAsOperator(
                "/api/operator-accounts/" + operatorId + "/enabled", Map.of("enabled", false), factoryId);
        assertThat(disabled.getBody()).containsEntry("enabled", false);
        assertThat(post("/api/operator-auth/login", Map.of(
                "username", "counter", "password", "changed-password")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsCaseInsensitiveUsernameConflictsAndProtectsFactoryAdmin() {
        long operatorId = createOperator("front-desk", "前台", "initial-password", true);

        ResponseEntity<Map<String, Object>> duplicate = postAsOperator("/api/operator-accounts", Map.of(
                "username", "FRONT-DESK",
                "displayName", "重名",
                "password", "another-password",
                "accountType", "CLERK"), factoryId);
        assertError(duplicate, HttpStatus.CONFLICT, "OPERATOR_USERNAME_CONFLICT");

        ResponseEntity<Map<String, Object>> renamedDuplicate = putAsOperator(
                "/api/operator-accounts/" + operatorId,
                Map.of("username", "FACTORY-TEST", "displayName", "前台"), factoryId);
        assertError(renamedDuplicate, HttpStatus.CONFLICT, "OPERATOR_USERNAME_CONFLICT");

        ResponseEntity<Map<String, Object>> disabledFactory = putAsOperator(
                "/api/operator-accounts/" + factoryId + "/enabled", Map.of("enabled", false), factoryId);
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
                "SELECT created_by FROM members WHERE id=?", String.class, number(created.getBody().get("id"))))
                .isEqualTo("operator:factory-test");
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM operator_action_logs");
        assertThat(audit)
                .containsEntry("operator_id", (int) factoryId)
                .containsEntry("action", "MEMBER_CREATED")
                .containsEntry("target_type", "MEMBER");
        assertThat(protectedData.decryptField("operator_action_logs", "operator_username", audit.get("operator_username")))
                .isEqualTo("factory-test");
        assertThat(protectedData.decryptField("operator_action_logs", "operator_display_name", audit.get("operator_display_name")))
                .isEqualTo("测试出厂管理员");

        jdbc.update("UPDATE operator_accounts SET display_name='已改名管理员' WHERE id=?", factoryId);
        assertThat(protectedData.decryptField("operator_action_logs", "operator_display_name",
                jdbc.queryForObject("SELECT operator_display_name FROM operator_action_logs", String.class)))
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
        assertError(response, HttpStatus.FORBIDDEN, "OPERATOR_SESSION_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_action_logs", Integer.class)).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void normalFailureDeniedAndExportLogsDoNotContainSensitiveValues(CapturedOutput output) {
        String phone = "13977112233";
        String name = "日志脱敏会员";
        String uid = "887766554433";
        String password = "never-log-this-password";
        long clerkId = number(postAsOperator("/api/operator-accounts", Map.of(
                "username", "log-safe-clerk", "displayName", "日志测试店员", "password", password,
                "accountType", "CLERK"), factoryId).getBody().get("id"));
        postAsOperator("/api/members", Map.of("phone", phone, "name", name), factoryId);
        postAsOperator("/api/members", Map.of("phone", phone, "name", name), factoryId);
        postAsOperator("/api/wristbands/charge", Map.of("uid", uid, "durationMinutes", 10), factoryId);
        export("members", factoryId);
        export("members", clerkId);

        assertThat(output.getAll()).doesNotContain(phone, name, uid, password,
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void logsAccountMemberWristbandAndRoomManagementActions() {
        long memberId = number(postAsOperator("/api/members", Map.of(
                "phone", "13700137000", "name", "综合留痕会员"), factoryId).getBody().get("id"));
        long operatorId = number(postAsOperator("/api/operator-accounts", Map.of(
                "username", "audit-user", "displayName", "留痕操作员", "password", "123456",
                "accountType", "CLERK"), factoryId)
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
        Object settingsTarget = jdbc.queryForMap(
                "SELECT action, target_id FROM operator_action_logs WHERE action='SYSTEM_SETTINGS_UPDATED'")
                .get("target_id");
        assertThat(protectedData.decryptField("operator_action_logs", "target_id", settingsTarget))
                .isEqualTo("child-mode");
        assertThat(jdbc.queryForList("SELECT summary_json FROM operator_action_logs").stream()
                .map(row -> String.valueOf(row.get("summary_json"))))
                .allSatisfy(summary -> assertThat(summary).doesNotContain("654321", "123456"));
    }

    @Test
    void exportsOperationalCsvOnlyForAnAuthorizedOperator() {
        ResponseEntity<Map<String, Object>> firstMember = postAsOperator("/api/members", Map.of(
                "phone", "13800138888", "name", "CSV,\"member\""), factoryId);
        postAsOperator("/api/members", Map.of(
                "phone", "13800138889", "name", "second member"), factoryId);
        long memberId = number(firstMember.getBody().get("id"));
        postAsOperator("/api/wristbands/charge", Map.of(
                "uid", "99887766", "durationMinutes", 7), factoryId);
        postAsOperator("/api/wristbands/bind", Map.of(
                "uid", "99887766", "memberId", memberId), factoryId);
        post("/api/game-access/activate", Map.of("uid", "99887766", "deviceId", "export-device"));
        ResponseEntity<Map<String, Object>> play = post("/api/game-plays/start", Map.of(
                "uid", "99887766",
                "deviceId", "export-device",
                "externalSessionId", "export-session",
                "gameId", "simple",
                "gameName", "CSV game"));
        put("/api/game-plays/" + number(play.getBody().get("id")) + "/result", Map.of(
                "success", true,
                "terminationReason", "NATURAL_COMPLETION",
                "rawScore", 42));
        long revisionBeforeExport = jdbc.queryForObject(
                "SELECT revision FROM database_state WHERE id=1", Long.class);

        ResponseEntity<byte[]> response = export("members", factoryId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("members.csv");
        assertThat(response.getBody()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(response.getBody(), 3, response.getBody().length - 3,
                StandardCharsets.UTF_8);
        assertThat(csv).contains("13800138888").contains("\"CSV,\"\"member\"\"\"");
        assertThat(csv.indexOf("13800138888")).isLessThan(csv.indexOf("13800138889"));
        assertThat(csv).contains(",42,1\r\n");
        assertThat(csv).doesNotContain("password_hash", "test-password");

        String charges = csvText(export("wristband-charges", factoryId));
        assertThat(charges).contains("99887766,7,100,700");
        String plays = csvText(export("game-plays", factoryId));
        assertThat(plays).contains("export-session").contains("CSV game")
                .contains(",42,42,raw-score-v1");
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM database_state WHERE id=1", Long.class))
                .isEqualTo(revisionBeforeExport + 3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_action_logs WHERE action='DATA_EXPORTED'",
                Integer.class)).isGreaterThanOrEqualTo(3);

        HttpHeaders invalidHeaders = new HttpHeaders();
        invalidHeaders.set("X-Operator-Id", "999999");
        ResponseEntity<byte[]> rejected = http.exchange(
                "/api/exports/members.csv", HttpMethod.GET,
                new HttpEntity<>(invalidHeaders), byte[].class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberExportReadsOneCommittedSnapshotWhileAnotherTransactionWrites() throws Exception {
        ResponseEntity<Map<String, Object>> member = postAsOperator("/api/members", Map.of(
                "phone", "13800139991", "name", "并发导出会员"), factoryId);
        long memberId = number(member.getBody().get("id"));
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO wristbands(card_uid, card_uid_lookup_hash, status, duration_minutes, charged_at, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', 60, ?, ?, ?)
            """, protectedData.encryptField("wristbands", "card_uid", "99009901"),
                protectedData.wristbandLookupHash("99009901"), now, now, now);
        long wristbandId = jdbc.queryForObject(
                "SELECT id FROM wristbands WHERE card_uid_lookup_hash=?", Long.class,
                protectedData.wristbandLookupHash("99009901"));
        jdbc.update("""
            INSERT INTO wristband_bindings(wristband_id, member_id, status, duration_minutes, bound_at, started_at)
            VALUES (?, ?, 'ACTIVE', 60, ?, ?)
            """, wristbandId, memberId, now, now);
        long bindingId = jdbc.queryForObject(
                "SELECT id FROM wristband_bindings WHERE wristband_id=?", Long.class, wristbandId);
        long revisionBefore = jdbc.queryForObject(
                "SELECT revision FROM database_state WHERE id=1", Long.class);

        CountDownLatch firstUncommittedRow = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                insertCompletedPlay(memberId, bindingId, "snapshot-a", 10, now);
                firstUncommittedRow.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                insertCompletedPlay(memberId, bindingId, "snapshot-b", 20, now);
            }));
            assertThat(firstUncommittedRow.await(5, TimeUnit.SECONDS)).isTrue();

            String duringWrite = csvText(exportService.members());
            assertThat(memberCsvPoints(duringWrite, "13800139991")).isIn(0, 30);

            writer.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        long revisionAfterWrites = jdbc.queryForObject(
                "SELECT revision FROM database_state WHERE id=1", Long.class);
        assertThat(revisionAfterWrites).isGreaterThan(revisionBefore);
        String afterCommit = csvText(exportService.members());
        assertThat(memberCsvPoints(afterCommit, "13800139991")).isEqualTo(30);
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM database_state WHERE id=1", Long.class)).isEqualTo(revisionAfterWrites);
    }

    private void insertCompletedPlay(long memberId, long bindingId, String sessionId, int points, String now) {
        jdbc.update("""
            INSERT INTO game_play_records(member_id, binding_id, wristband_uid, device_id,
                external_session_id, game_id, game_name, status, started_at, ended_at,
                success, termination_reason, raw_score, points_awarded, scoring_policy)
            VALUES (?, ?, ?, 'export-device', ?, 'simple', '并发快照',
                'COMPLETED', ?, ?, 1, 'NATURAL_COMPLETION', ?, ?, 'raw-score-v1')
            """, memberId, bindingId,
                protectedData.encryptField("game_play_records", "wristband_uid", "99009901"),
                sessionId, now, now, points, points);
    }

    private static int memberCsvPoints(String csv, String phone) {
        String row = csv.lines().filter(line -> line.contains(phone)).findFirst().orElseThrow();
        String[] values = row.split(",", -1);
        return Integer.parseInt(values[values.length - 2]);
    }

    private ResponseEntity<byte[]> export(String dataset, long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator-Id", String.valueOf(operatorId));
        return http.exchange("/api/exports/" + dataset + ".csv", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
    }

    private static String csvText(ResponseEntity<byte[]> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] body = response.getBody();
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        return new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
    }

    private static String csvText(byte[] body) {
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        return new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
    }

    private long createOperator(String username, String displayName, String password, boolean enabled) {
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO operator_accounts(
                username, display_name, password_hash, account_type,
                enabled, created_by_operator_id, created_at, updated_at)
            VALUES (?, ?, ?, 'CLERK', ?, ?, ?, ?)
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

    private static HttpEntity<Void> operatorEntity(long operatorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator-Id", String.valueOf(operatorId));
        return new HttpEntity<>(headers);
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
