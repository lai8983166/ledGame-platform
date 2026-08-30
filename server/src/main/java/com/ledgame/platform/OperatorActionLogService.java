package com.ledgame.platform;

import java.time.Clock;
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

    public OperatorActionLogService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OperatorSnapshot resolve(long operatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name
              FROM operator_accounts WHERE id=?
            """, operatorId);
        if (rows.isEmpty()) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "OPERATOR_CONTEXT_INVALID",
                    "当前操作账号不存在，请退出后重新登录");
        }
        Map<String, Object> row = rows.get(0);
        return new OperatorSnapshot(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")), String.valueOf(row.get("display_name")));
    }

    @Transactional
    public void record(OperatorSnapshot operator, OperatorAuditAction action, String method, String path) {
        jdbc.update("""
            INSERT INTO operator_action_logs(
                operator_id, operator_username, operator_display_name,
                action, target_type, target_id, summary_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, operator.id(), operator.username(), operator.displayName(), action.action(),
                action.targetType(), action.targetId(), summary(method, path), clock.instant().toString());
    }

    private String summary(String method, String path) {
        try {
            return objectMapper.writeValueAsString(Map.of("method", method, "path", path));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize operator action summary", exception);
        }
    }
}
