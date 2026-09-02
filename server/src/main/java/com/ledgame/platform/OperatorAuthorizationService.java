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
        if (operatorId == null || operatorId <= 0) throw forbidden();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, username, display_name, account_type, enabled
              FROM operator_accounts WHERE id=?
            """, operatorId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("enabled")).intValue() == 0) throw forbidden();
        Map<String, Object> row = rows.get(0);
        return new AuthorizedOperator(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")), String.valueOf(row.get("display_name")),
                String.valueOf(row.get("account_type")));
    }

    public AuthorizedOperator requireFactory(Long operatorId) {
        AuthorizedOperator operator = require(operatorId);
        if (!"FACTORY_ADMIN".equals(operator.accountType())) throw forbidden();
        return operator;
    }

    private static PlatformApiException forbidden() {
        return new PlatformApiException(HttpStatus.FORBIDDEN, BackupErrorCode.IMPORT_FORBIDDEN.name(),
                BackupErrorCode.IMPORT_FORBIDDEN.defaultMessage());
    }

    public record AuthorizedOperator(long id, String username, String displayName, String accountType) {}
}
