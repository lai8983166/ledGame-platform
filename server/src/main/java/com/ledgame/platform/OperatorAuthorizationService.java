package com.ledgame.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperatorAuthorizationService {
    private final JdbcTemplate jdbc;

    public OperatorAuthorizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuthorizedOperator require(Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw invalidSession();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name, account_type, enabled
              FROM operator_accounts WHERE id=? AND deleted_at IS NULL
            """, operatorId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("enabled")).intValue() == 0) throw invalidSession();
        Map<String, Object> row = rows.get(0);
        return new AuthorizedOperator(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")), String.valueOf(row.get("display_name")),
                String.valueOf(row.get("account_type")));
    }

    public AuthorizedOperator requireFactory(Long operatorId) {
        return requireCapability(operatorId, OperatorCapability.FACTORY_MAINTENANCE);
    }

    public AuthorizedOperator requireCapability(Long operatorId, OperatorCapability capability) {
        AuthorizedOperator operator = require(operatorId);
        OperatorRole role = OperatorRole.valueOf(operator.accountType());
        if (!allowed(role, capability)) throw forbidden();
        return operator;
    }

    public boolean canManage(AuthorizedOperator actor, OperatorRole targetRole) {
        OperatorRole actorRole = OperatorRole.valueOf(actor.accountType());
        return actorRole == OperatorRole.FACTORY_ADMIN
                ? targetRole != OperatorRole.FACTORY_ADMIN
                : actorRole == OperatorRole.STORE_MANAGER && targetRole == OperatorRole.CLERK;
    }

    static boolean allowed(OperatorRole role, OperatorCapability capability) {
        if (role == OperatorRole.FACTORY_ADMIN) return true;
        return switch (capability) {
            case WRISTBAND_MANAGE, MEMBER_MANAGE, FEATURE_SETTINGS -> true;
            case OPERATIONS_VIEW, DATA_EXPORT -> role == OperatorRole.STORE_MANAGER;
            case MEMBER_DELETE, FACTORY_MAINTENANCE -> false;
        };
    }

    private static PlatformApiException forbidden() {
        return new PlatformApiException(HttpStatus.FORBIDDEN, "OPERATOR_FORBIDDEN",
                "当前账号没有执行此操作的权限");
    }

    private static PlatformApiException invalidSession() {
        return new PlatformApiException(HttpStatus.FORBIDDEN, "OPERATOR_SESSION_INVALID",
                "当前账号已停用、删除或不存在，请重新登录");
    }

    public record AuthorizedOperator(long id, String username, String displayName, String accountType) {}
}
