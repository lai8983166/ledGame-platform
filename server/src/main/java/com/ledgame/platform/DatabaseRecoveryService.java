package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Coordinates the customer-side request/session and one-time vendor response import. */
@Service
public class DatabaseRecoveryService {
    private static final Duration REQUEST_TTL = Duration.ofMinutes(30);
    private final DatabaseRecoveryKeyService recoveryKeys;
    private final DatabaseBackupEngine engine;
    private final DatabaseFileInspector inspector;
    private final DatabaseImportService imports;
    private final DataProtectionKeyManager dataKeys;
    private final WindowsDataProtector protector;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OperatorActionLogService auditLogs;
    private final Path sessionsDirectory;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public DatabaseRecoveryService(DatabaseRecoveryKeyService recoveryKeys,
            DatabaseBackupEngine engine,
            DatabaseFileInspector inspector,
            DatabaseImportService imports,
            DataProtectionKeyManager dataKeys,
            WindowsDataProtector protector,
            ObjectMapper objectMapper,
            Clock clock) {
        this(recoveryKeys, engine, inspector, imports, dataKeys, protector, objectMapper, clock, null);
    }

    @Autowired
    public DatabaseRecoveryService(DatabaseRecoveryKeyService recoveryKeys,
            DatabaseBackupEngine engine,
            DatabaseFileInspector inspector,
            DatabaseImportService imports,
            DataProtectionKeyManager dataKeys,
            WindowsDataProtector protector,
            ObjectMapper objectMapper,
            Clock clock,
            OperatorActionLogService auditLogs) {
        this.recoveryKeys = recoveryKeys;
        this.engine = engine;
        this.inspector = inspector;
        this.imports = imports;
        this.dataKeys = dataKeys;
        this.protector = protector;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditLogs = auditLogs;
        Path parent = engine.sourceDatabase().getParent();
        this.sessionsDirectory = (parent == null ? Path.of(".").toAbsolutePath() : parent)
                .resolve("security").resolve("recovery-sessions");
    }

    @PostConstruct
    void cleanupExpiredSessions() {
        try {
            if (!Files.isDirectory(sessionsDirectory)) return;
            Instant now = clock.instant();
            try (var stream = Files.list(sessionsDirectory)) {
                for (Path path : stream.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        Session session = objectMapper.readValue(path.toFile(), Session.class);
                        if (session.request() == null || session.request().expiresAt() == null
                                || now.isAfter(session.request().expiresAt())) Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // A malformed stale session is not usable and must not block startup.
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    public DatabaseRecoveryRequest createRequest(Path rawBackupDirectory) {
        return createRequest(rawBackupDirectory, null);
    }

    public DatabaseRecoveryRequest createRequest(Path rawBackupDirectory, OperatorSnapshot operator) {
        try {
            return createRequestInternal(rawBackupDirectory);
        } catch (PlatformApiException exception) {
            audit(operator, "DATABASE_RECOVERY_REQUEST_VALIDATION_FAILED", null,
                    "/api/database-recovery/request", exception.getCode());
            throw exception;
        } catch (RuntimeException exception) {
            audit(operator, "DATABASE_RECOVERY_REQUEST_FAILED", null,
                    "/api/database-recovery/request", "INTERNAL_ERROR");
            throw exception;
        }
    }

    private DatabaseRecoveryRequest createRequestInternal(Path rawBackupDirectory) {
        requireEnabled();
        Path backup = validateBackup(rawBackupDirectory);
        DatabaseBackupMetadata metadata = readMetadata(backup);
        InspectedDatabase inspected = inspector.inspect(backup.resolve("platform.db"));
        DatabaseRecoveryEnvelope envelope = readRecoveryEnvelope(backup, metadata);
        String databaseSha = hash(backup.resolve("platform.db"));
        if (!inspected.valid() || !databaseSha.equalsIgnoreCase(metadata.sha256())
                || !engine.acceptsMetadata(metadata)
                || metadata.instanceId() == null
                || !metadata.instanceId().equals(inspected.state().instanceId())
                || metadata.revision() != inspected.state().revision()) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_BACKUP_INVALID");
        }
        Instant created = clock.instant();
        DatabaseRecoveryRequest request;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String id = UUID.randomUUID().toString();
            request = new DatabaseRecoveryRequest(DatabaseRecoveryRequest.FORMAT, id,
                    envelope.recoveryKeyId(), metadata.instanceId(), metadata.revision(), metadata.keyId(),
                    databaseSha, hash(backup.resolve("metadata.json")),
                    hash(backup.resolve("factory-key-envelope.json")),
                    pem(pair.getPublic().getEncoded()), created, created.plus(REQUEST_TTL));
            Files.createDirectories(sessionsDirectory);
            Session session = new Session(request,
                    Base64.getEncoder().encodeToString(protector.protect(pair.getPrivate().getEncoded())));
            writeSession(id, session);
            return request;
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_RECOVERY_REQUEST_CREATE_FAILED", exception);
        }
    }

    public DatabaseImportManifest importResponse(Path rawBackupDirectory, Path rawResponseFile) {
        return importResponse(rawBackupDirectory, rawResponseFile, null);
    }

    /** Returns the response request id for audit correlation without validating or importing it. */
    public String responseRequestId(Path rawResponseFile) {
        if (rawResponseFile == null) return null;
        try {
            DatabaseRecoveryResponse response = objectMapper.readValue(
                    rawResponseFile.toAbsolutePath().normalize().toFile(), DatabaseRecoveryResponse.class);
            return response == null ? null : response.requestId();
        } catch (Exception ignored) {
            return null;
        }
    }

    public DatabaseImportManifest importResponse(Path rawBackupDirectory, Path rawResponseFile,
            OperatorSnapshot operator) {
        Path backup = null;
        Path responsePath = null;
        String auditRequestId = null;
        boolean stagingStarted = false;
        Path temporaryKey = null;
        try {
            requireEnabled();
            backup = validateBackup(rawBackupDirectory);
            responsePath = rawResponseFile == null ? null : rawResponseFile.toAbsolutePath().normalize();
            if (responsePath == null || !Files.isRegularFile(responsePath)) {
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_RESPONSE_MISSING");
            }
            DatabaseRecoveryResponse response = objectMapper.readValue(responsePath.toFile(), DatabaseRecoveryResponse.class);
            auditRequestId = response == null ? null : response.requestId();
            validateResponse(response);
            Object lock = locks.computeIfAbsent(response.requestId(), ignored -> new Object());
            synchronized (lock) {
                Session session = readSession(response.requestId());
                if (session == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_REQUEST_NOT_FOUND");
                Instant now = clock.instant();
                if (now.isAfter(session.request().expiresAt()) || now.isAfter(response.expiresAt())) {
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_REQUEST_EXPIRED");
                }
                if (response.expiresAt().isAfter(session.request().expiresAt())) {
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_RESPONSE_MISMATCH");
                }
                DatabaseBackupMetadata metadata = readMetadata(backup);
                if (!session.request().databaseSha256().equalsIgnoreCase(hash(backup.resolve("platform.db")))
                        || !session.request().metadataSha256().equalsIgnoreCase(hash(backup.resolve("metadata.json")))
                        || !session.request().recoveryEnvelopeSha256().equalsIgnoreCase(hash(backup.resolve("factory-key-envelope.json")))
                        || metadata.revision() != session.request().revision()
                        || !metadata.keyId().equals(session.request().keyId())) {
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_BACKUP_CHANGED");
                }
                PrivateKey privateKey = decodePrivateKey(session.protectedPrivateKey());
                byte[] rawKey = decrypt(privateKey, response.wrappedKey());
                DataKeyMaterial material = DataProtectionKeyManager.material(rawKey);
                if (!material.keyId().equals(session.request().keyId())) {
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_KEY_ID_MISMATCH");
                }
                Path staging = engine.sourceDatabase().getParent().resolve("import-staging");
                Files.createDirectories(staging);
                temporaryKey = staging.resolve("recovery-" + response.requestId() + ".data-key.dpapi");
                dataKeys.writeEnvelope(temporaryKey, material);
                stagingStarted = true;
                try {
                    DatabaseBackupCandidate candidate = imports.registerExternal(backup.resolve("platform.db"), temporaryKey);
                    DatabaseImportManifest manifest = imports.prepare(candidate.candidateId());
                    Files.deleteIfExists(temporaryKey);
                    Files.deleteIfExists(sessionPath(response.requestId()));
                    return manifest;
                } catch (RuntimeException exception) {
                    throw exception;
                }
            }
        } catch (PlatformApiException exception) {
            audit(operator, stagingStarted ? "DATABASE_RECOVERY_ROLLED_BACK"
                    : isResponseValidationCode(exception.getCode())
                            ? "DATABASE_RECOVERY_RESPONSE_VALIDATION_FAILED"
                            : "DATABASE_RECOVERY_IMPORT_VALIDATION_FAILED",
                    auditRequestId, "/api/database-recovery/response/import", exception.getCode());
            throw exception;
        } catch (Exception exception) {
            audit(operator, stagingStarted ? "DATABASE_RECOVERY_ROLLED_BACK"
                    : "DATABASE_RECOVERY_RESPONSE_VALIDATION_FAILED", auditRequestId,
                    "/api/database-recovery/response/import", "DATABASE_RECOVERY_RESPONSE_INVALID");
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_RESPONSE_INVALID", exception);
        } finally {
            if (temporaryKey != null) {
                try {
                    Files.deleteIfExists(temporaryKey);
                } catch (Exception ignored) {
                    // Cleanup failure must not hide the original recovery result.
                }
            }
        }
    }

    /** Cancels a pending request and removes its DPAPI-protected ephemeral private key. */
    public void cancelRequest(String requestId) {
        cancelRequest(requestId, null);
    }

    public void cancelRequest(String requestId, OperatorSnapshot operator) {
        Object lock = locks.computeIfAbsent(String.valueOf(requestId), ignored -> new Object());
        synchronized (lock) {
            try {
                Files.deleteIfExists(sessionPath(requestId));
            } catch (PlatformApiException exception) {
                audit(operator, "DATABASE_RECOVERY_REQUEST_CANCEL_FAILED", requestId,
                        "/api/database-recovery/request/cancel", exception.getCode());
                throw exception;
            } catch (Exception exception) {
                audit(operator, "DATABASE_RECOVERY_REQUEST_CANCEL_FAILED", requestId,
                        "/api/database-recovery/request/cancel", "DATABASE_RECOVERY_SESSION_CLEANUP_FAILED");
                throw error(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_RECOVERY_SESSION_CLEANUP_FAILED", exception);
            }
        }
    }

    private static boolean isResponseValidationCode(String code) {
        return code != null && (code.startsWith("DATABASE_RECOVERY_RESPONSE_")
                || code.equals("DATABASE_RECOVERY_REQUEST_NOT_FOUND")
                || code.equals("DATABASE_RECOVERY_REQUEST_EXPIRED")
                || code.equals("DATABASE_RECOVERY_RESPONSE_MISMATCH"));
    }

    private void audit(OperatorSnapshot operator, String action, String targetId,
            String path, String outcome) {
        if (operator == null || auditLogs == null) return;
        try {
            auditLogs.record(operator, new OperatorAuditAction(action, "DATABASE_RECOVERY", targetId),
                    "RECOVERY", path, outcome);
        } catch (RuntimeException ignored) {
            // Auditing must never turn a recovery failure into a different failure.
        }
    }

    private Path validateBackup(Path raw) {
        Path backup = raw == null ? null : raw.toAbsolutePath().normalize();
        if (backup == null || !Files.isDirectory(backup)
                || !Files.isRegularFile(backup.resolve("platform.db"))
                || !Files.isRegularFile(backup.resolve("metadata.json"))
                || !Files.isRegularFile(backup.resolve("factory-key-envelope.json"))) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_BACKUP_INVALID");
        }
        return backup;
    }

    private DatabaseBackupMetadata readMetadata(Path backup) {
        try { return objectMapper.readValue(backup.resolve("metadata.json").toFile(), DatabaseBackupMetadata.class); }
        catch (Exception exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_METADATA_INVALID", exception); }
    }

    private DatabaseRecoveryEnvelope readRecoveryEnvelope(Path backup, DatabaseBackupMetadata metadata) {
        try {
            Path file = backup.resolve("factory-key-envelope.json");
            if (metadata.recoveryEnvelopeSha256() == null
                    || !metadata.recoveryEnvelopeSha256().equalsIgnoreCase(hash(file))) {
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_ENVELOPE_HASH_MISMATCH");
            }
            DatabaseRecoveryEnvelope envelope = recoveryKeys.read(file);
            if (!metadata.keyId().equals(envelope.keyId())
                    || !java.util.Objects.equals(metadata.recoveryKeyId(), envelope.recoveryKeyId())
                    || !java.util.Objects.equals(metadata.recoveryEnvelopeFormat(), envelope.format())) {
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_KEY_ID_MISMATCH");
            }
            return envelope;
        } catch (PlatformApiException exception) { throw exception; }
        catch (Exception exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_ENVELOPE_INVALID", exception); }
    }

    private void validateResponse(DatabaseRecoveryResponse response) {
        if (response == null || !DatabaseRecoveryResponse.FORMAT.equals(response.format())
                || response.requestId() == null || response.requestId().isBlank()
                || response.wrappedKey() == null || response.wrappedKey().isBlank()
                || response.expiresAt() == null) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_RESPONSE_INVALID");
        }
    }

    private PrivateKey decodePrivateKey(String protectedKey) {
        try {
            byte[] encoded = protector.unprotect(Base64.getDecoder().decode(protectedKey));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_SESSION_INVALID", exception); }
    }

    private static byte[] decrypt(PrivateKey privateKey, String wrappedKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(Base64.getUrlDecoder().decode(wrappedKey));
    }

    private void writeSession(String id, Session session) throws Exception {
        Path target = sessionPath(id);
        Path temporary = target.resolveSibling(target.getFileName() + ".writing");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), session);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Session readSession(String id) {
        try {
            Path path = sessionPath(id);
            if (!Files.isRegularFile(path)) return null;
            return objectMapper.readValue(path.toFile(), Session.class);
        } catch (Exception exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_SESSION_INVALID", exception); }
    }

    private Path sessionPath(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_REQUEST_INVALID");
        }
        return sessionsDirectory.resolve(id + ".json").normalize();
    }

    private void requireEnabled() {
        if (!recoveryKeys.enabled()) throw error(HttpStatus.NOT_IMPLEMENTED, "DATABASE_RECOVERY_DISABLED");
    }

    private String hash(Path path) {
        try { return inspector.sha256(path); }
        catch (Exception exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_RECOVERY_FILE_READ_FAILED", exception); }
    }

    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static PlatformApiException error(HttpStatus status, String code) {
        return new PlatformApiException(status, code, code);
    }

    private static PlatformApiException error(HttpStatus status, String code, Exception cause) {
        return new PlatformApiException(status, code, code);
    }

    public record Session(DatabaseRecoveryRequest request, String protectedPrivateKey) {}
}
