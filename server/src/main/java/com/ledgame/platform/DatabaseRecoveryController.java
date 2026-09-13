package com.ledgame.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Factory-only local endpoints for preparing and applying vendor-assisted recovery. */
@RestController
@RequestMapping("/api/database-recovery")
public class DatabaseRecoveryController {
    private final DatabaseRecoveryService recovery;
    private final OperatorAuthorizationService authorization;

    public DatabaseRecoveryController(DatabaseRecoveryService recovery,
            OperatorAuthorizationService authorization) {
        this.recovery = recovery;
        this.authorization = authorization;
    }

    @PostMapping("/request")
    public DatabaseRecoveryRequest createRequest(HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestBody PathRequest request) {
        requireLoopback(servletRequest);
        authorization.requireFactory(operatorId);
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_BACKUP_INVALID", "请选择有效的备份目录");
        }
        return recovery.createRequest(Path.of(request.path()));
    }

    @PostMapping("/response/import")
    public DatabaseImportManifest importResponse(HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestBody ResponseImportRequest request) {
        requireLoopback(servletRequest);
        authorization.requireFactory(operatorId);
        if (request == null || request.backupPath() == null || request.responsePath() == null) {
            throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_RESPONSE_INVALID", "请选择备份和厂家响应文件");
        }
        return recovery.importResponse(Path.of(request.backupPath()), Path.of(request.responsePath()));
    }

    @PostMapping("/request/cancel")
    public void cancelRequest(HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestBody CancelRequest request) {
        requireLoopback(servletRequest);
        authorization.requireFactory(operatorId);
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_RECOVERY_REQUEST_INVALID", "恢复请求编号无效");
        }
        recovery.cancelRequest(request.requestId());
    }

    public record PathRequest(String path) {}
    public record ResponseImportRequest(String backupPath, String responsePath) {}
    public record CancelRequest(String requestId) {}

    private static void requireLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (!("127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address))) {
            throw new PlatformApiException(HttpStatus.FORBIDDEN, "LOCAL_REQUEST_REQUIRED", "该维护接口只允许本机访问");
        }
    }
}
