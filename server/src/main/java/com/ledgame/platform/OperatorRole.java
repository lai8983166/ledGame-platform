package com.ledgame.platform;

public enum OperatorRole {
    FACTORY_ADMIN,
    STORE_MANAGER,
    CLERK;

    static OperatorRole parse(String value) {
        try {
            return OperatorRole.valueOf(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new PlatformApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "OPERATOR_ROLE_INVALID", "账号角色必须是 STORE_MANAGER 或 CLERK");
        }
    }
}
