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
        if (exception != null || response.getStatus() < 200 || response.getStatus() >= 300) return;
        Object operator = request.getAttribute(OPERATOR_ATTRIBUTE);
        Object action = request.getAttribute(ACTION_ATTRIBUTE);
        if (operator instanceof OperatorSnapshot snapshot && action instanceof OperatorAuditAction auditAction) {
            logs.record(snapshot, auditAction, request.getMethod(), request.getRequestURI());
        }
    }

    private static OperatorAuditAction actionFor(String rawMethod, String path) {
        String method = rawMethod.toUpperCase(Locale.ROOT);
        if (method.equals("POST") && path.equals("/api/operator-accounts")) return action("ACCOUNT_CREATED", "OPERATOR_ACCOUNT", null);
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+/password")) return action("ACCOUNT_PASSWORD_RESET", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+/enabled")) return action("ACCOUNT_ENABLED_CHANGED", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("PUT") && path.matches("/api/operator-accounts/\\d+")) return action("ACCOUNT_UPDATED", "OPERATOR_ACCOUNT", segment(path, 3));
        if (method.equals("POST") && path.equals("/api/members")) return action("MEMBER_CREATED", "MEMBER", null);
        if (method.equals("DELETE") && path.matches("/api/members/\\d+")) return action("MEMBER_DELETED", "MEMBER", segment(path, 3));
        if (method.equals("POST") && path.equals("/api/wristbands/charge")) return action("WRISTBAND_CHARGED", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/clear")) return action("WRISTBAND_BALANCE_CLEARED", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/unbind")) return action("WRISTBAND_UNBOUND", "WRISTBAND", null);
        if (method.equals("POST") && path.equals("/api/wristbands/reclaim")) return action("WRISTBAND_RECLAIMED", "WRISTBAND", null);
        if (method.equals("PUT") && path.startsWith("/api/rooms/")) return action("ROOM_RENAMED", "ROOM", path.substring("/api/rooms/".length()));
        if (method.equals("PUT") && path.equals("/api/feature-settings/child-mode")) return action("SYSTEM_SETTINGS_UPDATED", "SYSTEM_SETTINGS", "child-mode");
        if (method.equals("POST") && path.equals("/api/operator-actions/system-settings")) return action("SYSTEM_SETTINGS_UPDATED", "SYSTEM_SETTINGS", null);
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
