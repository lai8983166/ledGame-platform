package com.ledgame.platform;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorAccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public OperatorAccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public Map<String, Object> login(String rawUsername, String rawPassword) {
        String username = normalizeUsername(rawUsername, false);
        String password = rawPassword == null ? "" : rawPassword;
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name, password_hash, account_type, enabled
              FROM operator_accounts
             WHERE username=? COLLATE NOCASE
            """, username);
        if (rows.isEmpty()) {
            throw loginFailed();
        }
        Map<String, Object> row = rows.get(0);
        if (!asBoolean(row.get("enabled"))
                || !passwordEncoder.matches(password, String.valueOf(row.get("password_hash")))) {
            throw loginFailed();
        }
        return loginProfile(row);
    }

    public List<Map<String, Object>> listAccounts() {
        return jdbc.query("""
            SELECT id, username, display_name, account_type, enabled, created_at, updated_at
              FROM operator_accounts
             ORDER BY CASE account_type WHEN 'FACTORY_ADMIN' THEN 0 ELSE 1 END, id
            """, (resultSet, rowNumber) -> publicAccount(resultSet.getLong("id"),
                    resultSet.getString("username"), resultSet.getString("display_name"),
                    resultSet.getString("account_type"), resultSet.getInt("enabled") != 0,
                    resultSet.getString("created_at"), resultSet.getString("updated_at")));
    }

    @Transactional
    public Map<String, Object> createOperator(
            String rawUsername, String rawDisplayName, String rawPassword, Long createdByOperatorId) {
        String username = normalizeUsername(rawUsername, true);
        String displayName = normalizeDisplayName(rawDisplayName);
        String password = validatePassword(rawPassword);
        ensureUsernameAvailable(username, null);
        String now = now();
        try {
            jdbc.update("""
                INSERT INTO operator_accounts(
                    username, display_name, password_hash, account_type,
                    enabled, created_by_operator_id, created_at, updated_at)
                VALUES (?, ?, ?, 'OPERATOR', 1, ?, ?, ?)
                """, username, displayName, passwordEncoder.encode(password), createdByOperatorId, now, now);
        } catch (DataAccessException exception) {
            throw usernameConflict();
        }
        Long id = jdbc.queryForObject(
                "SELECT id FROM operator_accounts WHERE username=? COLLATE NOCASE", Long.class, username);
        return getAccount(id);
    }

    @Transactional
    public Map<String, Object> updateProfile(Long id, String rawUsername, String rawDisplayName) {
        Map<String, Object> existing = requireAccount(id);
        String username = normalizeUsername(rawUsername, true);
        String displayName = normalizeDisplayName(rawDisplayName);
        ensureUsernameAvailable(username, id);
        try {
            jdbc.update("""
                UPDATE operator_accounts
                   SET username=?, display_name=?, updated_at=?
                 WHERE id=?
                """, username, displayName, now(), id);
        } catch (DataAccessException exception) {
            throw usernameConflict();
        }
        if (existing.isEmpty()) {
            throw accountNotFound();
        }
        return getAccount(id);
    }

    @Transactional
    public Map<String, Object> resetPassword(Long id, String rawPassword) {
        requireAccount(id);
        String password = validatePassword(rawPassword);
        jdbc.update("UPDATE operator_accounts SET password_hash=?, updated_at=? WHERE id=?",
                passwordEncoder.encode(password), now(), id);
        return getAccount(id);
    }

    @Transactional
    public Map<String, Object> setEnabled(Long id, Boolean enabled) {
        Map<String, Object> existing = requireAccount(id);
        if ("FACTORY_ADMIN".equals(existing.get("account_type")) && !Boolean.TRUE.equals(enabled)) {
            throw new PlatformApiException(HttpStatus.CONFLICT, "FACTORY_ADMIN_PROTECTED",
                    "出厂管理员不能被停用");
        }
        if (enabled == null) {
            throw invalid("enabled 必须是布尔值");
        }
        jdbc.update("UPDATE operator_accounts SET enabled=?, updated_at=? WHERE id=?",
                enabled ? 1 : 0, now(), id);
        return getAccount(id);
    }

    public Map<String, Object> getAccount(Long id) {
        Map<String, Object> row = requireAccount(id);
        return publicAccount(number(row.get("id")), String.valueOf(row.get("username")),
                String.valueOf(row.get("display_name")), String.valueOf(row.get("account_type")),
                asBoolean(row.get("enabled")), String.valueOf(row.get("created_at")),
                String.valueOf(row.get("updated_at")));
    }

    private Map<String, Object> requireAccount(Long id) {
        if (id == null) {
            throw accountNotFound();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name, password_hash, account_type,
                   enabled, created_at, updated_at
              FROM operator_accounts WHERE id=?
            """, id);
        if (rows.isEmpty()) {
            throw accountNotFound();
        }
        return rows.get(0);
    }

    private void ensureUsernameAvailable(String username, Long excludedId) {
        Integer count = excludedId == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_accounts WHERE username=? COLLATE NOCASE",
                        Integer.class, username)
                : jdbc.queryForObject("""
                        SELECT COUNT(*) FROM operator_accounts
                         WHERE username=? COLLATE NOCASE AND id<>?
                        """, Integer.class, username, excludedId);
        if (count != null && count > 0) {
            throw usernameConflict();
        }
    }

    private static Map<String, Object> loginProfile(Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", number(row.get("id")));
        result.put("username", row.get("username"));
        result.put("displayName", row.get("display_name"));
        result.put("accountType", row.get("account_type"));
        return result;
    }

    private static Map<String, Object> publicAccount(
            long id, String username, String displayName, String accountType,
            boolean enabled, String createdAt, String updatedAt) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("username", username);
        result.put("displayName", displayName);
        result.put("accountType", accountType);
        result.put("enabled", enabled);
        result.put("createdAt", createdAt);
        result.put("updatedAt", updatedAt);
        return result;
    }

    private static String normalizeUsername(String rawUsername, boolean validateFormat) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (validateFormat && !username.matches("[\\p{L}\\p{N}._-]{3,32}")) {
            throw invalid("用户名需为 3 到 32 个字符，只能使用字母、数字、点、下划线或连字符");
        }
        return username;
    }

    private static String normalizeDisplayName(String rawDisplayName) {
        String displayName = rawDisplayName == null ? "" : rawDisplayName.trim();
        if (displayName.isEmpty() || displayName.length() > 40) {
            throw invalid("显示名称需为 1 到 40 个字符");
        }
        return displayName;
    }

    private static String validatePassword(String rawPassword) {
        String password = rawPassword == null ? "" : rawPassword;
        if (password.length() < 6 || password.length() > 72) {
            throw invalid("密码需为 6 到 72 个字符");
        }
        return password;
    }

    private String now() {
        return clock.instant().toString();
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : number(value) != 0;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static PlatformApiException loginFailed() {
        return new PlatformApiException(HttpStatus.UNAUTHORIZED, "OPERATOR_LOGIN_FAILED",
                "用户名或密码错误，或账号已停用");
    }

    private static PlatformApiException accountNotFound() {
        return new PlatformApiException(HttpStatus.NOT_FOUND, "OPERATOR_ACCOUNT_NOT_FOUND", "操作账号不存在");
    }

    private static PlatformApiException usernameConflict() {
        return new PlatformApiException(HttpStatus.CONFLICT, "OPERATOR_USERNAME_CONFLICT", "用户名已存在");
    }

    private static PlatformApiException invalid(String message) {
        return new PlatformApiException(HttpStatus.BAD_REQUEST, "OPERATOR_ACCOUNT_INVALID", message);
    }
}
