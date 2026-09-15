package com.ledgame.platform;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class OperatorAuditInterceptor implements HandlerInterceptor {
    public static final String OPERATOR_ATTRIBUTE = "ledgame.operator.snapshot";
    /** Optional request attribute used by controllers to attach a resource id to the audit row. */
    public static final String TARGET_ID_ATTRIBUTE = OperatorAuditInterceptor.class.getName() + ".targetId";
    /** Set by recovery controllers when the service already persisted a stage-specific failure event. */
    public static final String FAILURE_RECORDED_ATTRIBUTE = OperatorAuditInterceptor.class.getName() + ".failureRecorded";
    private static final String ACTION_ATTRIBUTE = OperatorAuditInterceptor.class.getName() + ".action";

    private final OperatorActionLogService logs;

    public OperatorAuditInterceptor(OperatorActionLogService logs) {
        this.logs = logs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String rawOperatorId = request.getHeader("X-Operator-Id");
        if (rawOperatorId == null || rawOperatorId.isBlank()) {
            return true;
        }
        long operatorId;
        try {
            operatorId = Long.parseLong(rawOperatorId.trim());
            if (operatorId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw invalidContext();
        }
        request.setAttribute(OPERATOR_ATTRIBUTE, logs.resolve(operatorId));
        OperatorAuditAction action = actionFor(request.getMethod(), request.getRequestURI());
        if (action != null) request.setAttribute(ACTION_ATTRIBUTE, action);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        Object operator = request.getAttribute(OPERATOR_ATTRIBUTE);
        Object action = request.getAttribute(ACTION_ATTRIBUTE);
        if (operator instanceof OperatorSnapshot snapshot && action instanceof OperatorAuditAction auditAction) {
            Object targetId = request.getAttribute(TARGET_ID_ATTRIBUTE);
            if (targetId instanceof String value && !value.isBlank()) {
                auditAction = new OperatorAuditAction(auditAction.action(), auditAction.targetType(), value);
            }
            if (exception == null && response.getStatus() >= 200 && response.getStatus() < 300) {
                logs.record(snapshot, auditAction, request.getMethod(), request.getRequestURI());
            } else if (response.getStatus() == HttpStatus.FORBIDDEN.value()) {
                logs.record(snapshot,
                        new OperatorAuditAction(auditAction.action() + "_DENIED",
                                auditAction.targetType(), auditAction.targetId()),
                        request.getMethod(), request.getRequestURI());
            } else if (auditAction.action().startsWith("DATABASE_RECOVERY_")
                    && !Boolean.TRUE.equals(request.getAttribute(FAILURE_RECORDED_ATTRIBUTE))) {
                logs.record(snapshot,
                        new OperatorAuditAction(auditAction.action() + "_FAILED",
                                auditAction.targetType(), auditAction.targetId()),
                        request.getMethod(), request.getRequestURI());
            }
        }
    }

    private static OperatorAuditAction actionFor(String rawMethod, String path) {
        String method = rawMethod.toUpperCase(Locale.ROOT);
        if (method.equals("POST") && path.equals("/api/operator-accounts")) return action("ACCOUNT_CREATED", "OPERATOR_ACCOUNT", null);
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+/password")) return action("ACCOUNT_PASSWORD_RESET", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+/enabled")) return action("ACCOUNT_ENABLED_CHANGED", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+")) return action("ACCOUNT_UPDATED", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("DELETE") && path.matches("/api/operator-accounts/\\d+")) return action("ACCOUNT_DELETED", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("POST") && path.equals("/api/members")) return action("MEMBER_CREATED", "MEMBER", null);
        if (method.equals("PUT") && path.matches("/api/members/\\d+")) return action("MEMBER_UPDATED", "MEMBER", segment(path, 3));
        if (method.equals("DELETE") && path.matches("/api/members/\\d+")) return action("MEMBER_DELETED", "MEMBER", segment(path, 3));
        if (method.equals("POST") && path.equals("/api/wristbands/charge")) return action("WRISTBAND_CHARGED", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/clear")) return action("WRISTBAND_BALANCE_CLEARED", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/unbind")) return action("WRISTBAND_UNBOUND", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/reclaim")) return action("WRISTBAND_RECLAIMED", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/database-recovery/request")) {
            return action("DATABASE_RECOVERY_REQUEST_CREATED", "DATABASE_RECOVERY", null);
        }
        if (method.equals("POST") && path.equals("/api/database-recovery/response/import")) {
            return action("DATABASE_RECOVERY_RESPONSE_IMPORTED", "DATABASE_RECOVERY", null);
        }
        if (method.equals("POST") && path.equals("/api/database-recovery/request/cancel")) {
            return action("DATABASE_RECOVERY_REQUEST_CANCELLED", "DATABASE_RECOVERY", null);
        }
        if (method.equals("PUT") && path.startsWith("/api/rooms/")) return action("ROOM_RENAMED", "ROOM", path.substring("/api/rooms/".length()));
        if (method.equals("PUT") && path.equals("/api/feature-settings/child-mode")) return action("SYSTEM_SETTINGS_UPDATED", "SYSTEM_SETTINGS", "child-mode");
        if (method.equals("PATCH") && path.equals("/api/store-settings")) return action("STORE_SETTINGS_UPDATED", "STORE_SETTINGS", "1");
        if (method.equals("POST") && path.equals("/api/operator-actions/system-settings")) return action("SYSTEM_SETTINGS_UPDATED", "SYSTEM_SETTINGS", null);
        if (method.equals("GET") && path.matches("/api/exports/(members|wristband-charges|game-plays)\\.csv")) {
            return action("DATA_EXPORTED", "EXPORT_DATASET",
                    path.substring("/api/exports/".length(), path.length() - ".csv".length()));
        }
        return null;
    }

    private static OperatorAuditAction action(String action, String targetType, String targetId) {
        return new OperatorAuditAction(action, targetType, targetId);
    }

    private static String segment(String path, int index) {
        String[] segments = path.split("/");
        return segments.length > index ? segments[index] : null;
    }

    private static PlatformApiException invalidContext() {
        return new PlatformApiException(HttpStatus.BAD_REQUEST, "OPERATOR_CONTEXT_INVALID",
                "当前操作账号无效，请退出后重新登录");
    }
}
