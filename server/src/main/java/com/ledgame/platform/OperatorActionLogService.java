package com.ledgame.platform;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorActionLogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProtectedDataService protectedData;

    public OperatorActionLogService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock,
            ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.protectedData = protectedData;
    }

    public OperatorSnapshot resolve(long operatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name
              FROM operator_accounts WHERE id=? AND enabled=1 AND deleted_at IS NULL
            """, operatorId);
        if (rows.isEmpty()) {
            throw new PlatformApiException(HttpStatus.FORBIDDEN, "OPERATOR_SESSION_INVALID",
                    "当前账号已停用、删除或不存在，请重新登录");
        }
        Map<String, Object> row = rows.get(0);
        return new OperatorSnapshot(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")), String.valueOf(row.get("display_name")));
    }

    @Transactional
    public void record(OperatorSnapshot operator, OperatorAuditAction action, String method, String path) {
        record(operator, action, method, path, null);
    }

    /**
     * Records an operator action with a short, non-sensitive outcome marker.
     * The marker is stored in the encrypted summary alongside the request
     * method/path; callers must pass error codes or stage names only, never
     * exception messages or business data.
     */
    @Transactional
    public void record(OperatorSnapshot operator, OperatorAuditAction action,
            String method, String path, String outcome) {
        jdbc.update("""
            INSERT INTO operator_action_logs(
                operator_id, operator_username, operator_display_name,
                action, target_type, target_id, summary_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, operator.id(),
                protectedData.encryptField("operator_action_logs", "operator_username", operator.username()),
                protectedData.encryptField("operator_action_logs", "operator_display_name", operator.displayName()),
                action.action(), action.targetType(),
                protectedData.encryptField("operator_action_logs", "target_id", action.targetId()),
                protectedData.encryptField("operator_action_logs", "summary_json", summary(method, path, outcome)),
                clock.instant().toString());
    }

    private String summary(String method, String path, String outcome) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("method", method);
            values.put("path", path);
            if (outcome != null && !outcome.isBlank()) values.put("outcome", outcome);
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize operator action summary", exception);
        }
    }
}
