package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseRecoveryServiceTest {
    @TempDir Path root;

    @Test
    void requestContainsOnlyBindingMetadataAndCreatesProtectedSession() throws Exception {
        Path backup = Files.createDirectories(root.resolve("latest"));
        Files.write(backup.resolve("platform.db"), "encrypted-db-placeholder".getBytes(StandardCharsets.UTF_8));
        byte[] rawKey = new byte[32];
        DataKeyMaterial material = DataProtectionKeyManager.material(rawKey);
        DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", 1, "instance", 9,
                Instant.parse("2026-09-13T10:00:00Z"), null, null, Instant.parse("2026-09-13T10:01:00Z"),
                "source", "disk", Files.size(backup.resolve("platform.db")), sha(backup.resolve("platform.db")), "ok",
                ProtectedDataService.ENCRYPTION_VERSION, material.keyId(), "factory-recovery-v1",
                DatabaseRecoveryEnvelope.FORMAT, "c".repeat(64));
        DatabaseFileInspector inspector = mock(DatabaseFileInspector.class);
        String databaseHash = sha(backup.resolve("platform.db"));
        when(inspector.sha256(backup.resolve("platform.db"))).thenReturn(databaseHash);
        when(inspector.sha256(backup.resolve("metadata.json"))).thenReturn("b".repeat(64));
        when(inspector.sha256(backup.resolve("factory-key-envelope.json"))).thenReturn("c".repeat(64));
        when(inspector.inspect(backup.resolve("platform.db"))).thenReturn(new InspectedDatabase(
                backup.resolve("platform.db"), new DatabaseStateSnapshot("instance", 9, null, null, null),
                Files.size(backup.resolve("platform.db")), databaseHash, 1, true, "ok"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(backup.resolve("metadata.json"), mapper.writeValueAsString(metadata));
        Files.writeString(backup.resolve("factory-key-envelope.json"), mapper.writeValueAsString(
                new DatabaseRecoveryEnvelope(DatabaseRecoveryEnvelope.FORMAT, DatabaseRecoveryEnvelope.ALGORITHM,
                        "factory-recovery-v1", material.keyId(), "A".repeat(256))));
        DatabaseRecoveryProperties recoveryProperties = new DatabaseRecoveryProperties();
        Path publicKey = root.resolve("public.pem");
        var keys = java.security.KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var pair = keys.generateKeyPair();
        Files.writeString(publicKey, pem(pair.getPublic().getEncoded()));
        recoveryProperties.setPublicKeyPath(publicKey.toString());
        DatabaseRecoveryKeyService recoveryKeys = new DatabaseRecoveryKeyService(recoveryProperties, mapper);
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.acceptsMetadata(any())).thenReturn(true);
        when(engine.sourceDatabase()).thenReturn(root.resolve("main.db"));
        WindowsDataProtector protector = mock(WindowsDataProtector.class);
        when(protector.protect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DatabaseRecoveryService service = new DatabaseRecoveryService(recoveryKeys, engine, inspector,
                mock(DatabaseImportService.class), mock(DataProtectionKeyManager.class), protector, mapper,
                Clock.fixed(Instant.parse("2026-09-13T11:00:00Z"), ZoneOffset.UTC));

        DatabaseRecoveryRequest request = service.createRequest(backup);

        assertThat(request.requestId()).isNotBlank();
        assertThat(request.keyId()).isEqualTo(material.keyId());
        assertThat(request.temporaryPublicKey()).contains("BEGIN PUBLIC KEY");
        assertThat(mapper.writeValueAsString(request)).doesNotContain("encrypted-db-placeholder");
        assertThat(Files.list(root.resolve("security/recovery-sessions")).findAny()).isPresent();

        service.cancelRequest(request.requestId());
        try (var sessions = Files.list(root.resolve("security/recovery-sessions"))) {
            assertThat(sessions).noneMatch(path -> path.getFileName().toString().contains(request.requestId()));
        }
    }

    @Test
    void matchingVendorResponseIsConsumedAndPreparedWithTheRecoveredKey() throws Exception {
        Path backup = Files.createDirectories(root.resolve("latest-import"));
        Path database = Files.writeString(backup.resolve("platform.db"), "encrypted-db-placeholder");
        byte[] rawKey = new byte[32];
        DataKeyMaterial material = DataProtectionKeyManager.material(rawKey);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String databaseHash = sha(database);
        Path envelopePath = backup.resolve("factory-key-envelope.json");
        Files.writeString(envelopePath, mapper.writeValueAsString(new DatabaseRecoveryEnvelope(
                DatabaseRecoveryEnvelope.FORMAT, DatabaseRecoveryEnvelope.ALGORITHM,
                "factory-recovery-v1", material.keyId(), "A".repeat(256))));
        Files.writeString(backup.resolve("metadata.json"), mapper.writeValueAsString(new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", 1, "instance", 9,
                Instant.parse("2026-09-13T10:00:00Z"), null, null, Instant.parse("2026-09-13T10:01:00Z"),
                "source", "disk", Files.size(database), databaseHash, "ok",
                ProtectedDataService.ENCRYPTION_VERSION, material.keyId(), "factory-recovery-v1",
                DatabaseRecoveryEnvelope.FORMAT, sha(envelopePath))));

        Path publicKey = root.resolve("import-public.pem");
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Files.writeString(publicKey, pem(generator.generateKeyPair().getPublic().getEncoded()));
        DatabaseRecoveryProperties properties = new DatabaseRecoveryProperties();
        properties.setPublicKeyPath(publicKey.toString());
        DatabaseRecoveryKeyService recoveryKeys = new DatabaseRecoveryKeyService(properties, mapper);
        DatabaseFileInspector inspector = mock(DatabaseFileInspector.class);
        when(inspector.sha256(any(Path.class))).thenAnswer(invocation -> sha(invocation.getArgument(0)));
        when(inspector.inspect(database)).thenReturn(new InspectedDatabase(database,
                new DatabaseStateSnapshot("instance", 9, null, null, null), Files.size(database), databaseHash, 1, true, "ok"));
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.acceptsMetadata(any())).thenReturn(true);
        when(engine.sourceDatabase()).thenReturn(root.resolve("main.db"));
        WindowsDataProtector protector = mock(WindowsDataProtector.class);
        when(protector.protect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(protector.unprotect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        DatabaseImportService imports = mock(DatabaseImportService.class);
        when(imports.registerExternal(any(Path.class), any(Path.class))).thenReturn(
                new DatabaseBackupCandidate("candidate", "EXTERNAL", 9, null, null,
                        Files.size(database), "PRODUCTION", "admin", 0, true));
        DatabaseImportManifest manifest = new DatabaseImportManifest("prepared.db", "prepared.key", "key-sha", "db-sha");
        when(imports.prepare("candidate")).thenReturn(manifest);
        DatabaseRecoveryService service = new DatabaseRecoveryService(recoveryKeys, engine, inspector, imports,
                keyManager, protector, mapper,
                Clock.fixed(Instant.parse("2026-09-13T11:00:00Z"), ZoneOffset.UTC));
        DatabaseRecoveryRequest request = service.createRequest(backup);

        byte[] wrapped = encrypt(request.temporaryPublicKey(), rawKey);
        DatabaseRecoveryResponse unsigned = new DatabaseRecoveryResponse(
                DatabaseRecoveryResponse.FORMAT, request.requestId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped), request.expiresAt());
        Path responsePath = root.resolve("response.json");
        Files.writeString(responsePath, mapper.writeValueAsString(unsigned));

        assertThat(service.importResponse(backup, responsePath)).isEqualTo(manifest);
        verify(imports).prepare("candidate");
        try (var sessions = Files.list(root.resolve("security/recovery-sessions"))) {
            assertThat(sessions).isEmpty();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importResponse(backup, responsePath))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DATABASE_RECOVERY_REQUEST_NOT_FOUND"));
    }

    private static String sha(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static byte[] encrypt(String publicPem, byte[] key) throws Exception {
        String body = publicPem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(key);
    }

}
