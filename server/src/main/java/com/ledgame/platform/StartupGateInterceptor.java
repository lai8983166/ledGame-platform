package com.ledgame.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class StartupGateInterceptor implements HandlerInterceptor {
    private final StartupGate gate;

    public StartupGateInterceptor(StartupGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (alwaysAllowed(path)) return true;
        BackupLifecycleState state = gate.status().state();
        if (state == BackupLifecycleState.READY_PROTECTED || state == BackupLifecycleState.READY_DEGRADED) {
            return true;
        }
        if (state == BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED
                && (path.startsWith("/api/database-backup/") || path.equals("/api/database-backup"))) {
            return true;
        }
        if (state == BackupLifecycleState.IMPORTING
                && path.startsWith("/api/database-backup/")) return true;
        if (state == BackupLifecycleState.CHECKING) {
            throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLATFORM_STARTUP_CHECKING", "平台正在检查数据备份，请稍后重试");
        }
        if (state == BackupLifecycleState.MAINTENANCE_LOGIN_REQUIRED) {
            throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLATFORM_MAINTENANCE_LOGIN_REQUIRED", "请使用出厂账号登录并处理数据库备份");
        }
        if (state == BackupLifecycleState.IMPORTING) {
            throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLATFORM_DATABASE_IMPORTING", "正在导入数据库，请稍后重试");
        }
        throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PLATFORM_DATABASE_BLOCKED", gate.status().message());
    }

    private static boolean alwaysAllowed(String path) {
        return path.equals("/api/health")
                || path.equals("/api/system/startup-status")
                || path.equals("/api/system/database-backup/flush")
                || path.equals("/api/operator-auth/login");
    }
}
