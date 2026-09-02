package com.ledgame.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DatabaseBackupController {
    private final DatabaseBackupCoordinator coordinator;
    private final OperatorAuthorizationService authorization;
    private final DatabaseImportService imports;

    public DatabaseBackupController(
            DatabaseBackupCoordinator coordinator,
            OperatorAuthorizationService authorization,
            DatabaseImportService imports) {
        this.coordinator = coordinator;
        this.authorization = authorization;
        this.imports = imports;
    }

    @GetMapping("/system/startup-status")
    public BackupStatusSnapshot startupStatus(HttpServletRequest request) {
        requireLoopback(request);
        return coordinator.status();
    }

    @GetMapping("/database-backup/status")
    public BackupStatusSnapshot backupStatus(
            HttpServletRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireLoopback(request);
        authorization.require(operatorId);
        return coordinator.status();
    }

    @PostMapping("/system/database-backup/flush")
    public Map<String, Object> flush(HttpServletRequest request) {
        requireLoopback(request);
        return Map.of("completed", coordinator.flush());
    }

    @GetMapping("/database-backup/candidates")
    public List<DatabaseBackupCandidate> candidates(
            HttpServletRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireLoopback(request);
        authorization.requireFactory(operatorId);
        return imports.discoverFixedCandidates();
    }

    @PostMapping("/database-backup/candidates/external")
    public DatabaseBackupCandidate registerExternal(
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @org.springframework.web.bind.annotation.RequestBody ExternalCandidateRequest request) {
        requireLoopback(servletRequest);
        authorization.requireFactory(operatorId);
        return imports.registerExternal(Path.of(request.path()));
    }

    @PostMapping("/database-backup/candidates/{candidateId}/prepare")
    public DatabaseImportManifest prepareImport(
            HttpServletRequest request,
            @org.springframework.web.bind.annotation.PathVariable String candidateId,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireLoopback(request);
        authorization.requireFactory(operatorId);
        return imports.prepare(candidateId);
    }

    @PostMapping("/database-backup/import/cancel")
    public Map<String, Object> cancelImport(
            HttpServletRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireLoopback(request);
        authorization.requireFactory(operatorId);
        imports.cancelImport();
        return Map.of("cancelled", true);
    }

    @PostMapping("/database-backup/conflicts/use-current")
    public BackupStatusSnapshot keepCurrentDatabase(
            HttpServletRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireLoopback(request);
        authorization.requireFactory(operatorId);
        return coordinator.keepCurrentDatabase();
    }

    public record ExternalCandidateRequest(String path) {}

    private static void requireLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (!("127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address))) {
            throw new PlatformApiException(HttpStatus.FORBIDDEN, "LOCAL_REQUEST_REQUIRED", "该维护接口只允许本机访问");
        }
    }
}
