package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * A complete recovery rehearsal using only temporary user data, SQLite and
 * recovery material. It deliberately never points at the normal AppData or
 * production backup roots.
 */
class DatabaseRecoveryIsolatedE2ETest {
    @TempDir Path root;

    @Test
    void isolatedBackupRequestVendorResponseImportRestartAndReplayRejection() throws Exception {
        Path userData = Files.createDirectories(root.resolve("userData"));
        Path source = userData.resolve("data/platform.db");
        Files.createDirectories(source.getParent());
        Path isolatedBackup = Files.createDirectories(root.resolve("backup-test/member-admin"));
        Path formalRoot = root.resolve("formal-never-touch");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + source.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WindowsDataProtector protector = mock(WindowsDataProtector.class);
        when(protector.protect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(protector.unprotect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DataProtectionProperties protectionProperties = new DataProtectionProperties();
        protectionProperties.setKeyPath(userData.resolve("security/data-key.dpapi").toString());
        DataProtectionKeyManager keys = new DataProtectionKeyManager(protectionProperties, protector, mapper,
                "jdbc:sqlite:" + source.toAbsolutePath());
        DataKeyMaterial storeKey = DataProtectionKeyManager.material(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 });
        keys.install(storeKey);
        ProtectedDataService protectedData = new ProtectedDataService(keys);
        new DataProtectionMigration(jdbc, keys, protectedData,
                Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC))
                .run(new DefaultApplicationArguments());

        KeyPair vendorPair = rsaKeyPair();
        Path vendorPublic = userData.resolve("recovery-public.pem");
        Files.writeString(vendorPublic, pem("PUBLIC KEY", vendorPair.getPublic().getEncoded()));
        DatabaseRecoveryProperties recoveryProperties = new DatabaseRecoveryProperties();
        recoveryProperties.setPublicKeyPath(vendorPublic.toString());
        DatabaseRecoveryKeyService recoveryKeys = new DatabaseRecoveryKeyService(recoveryProperties, mapper);

        DatabaseBackupProperties backupProperties = new DatabaseBackupProperties();
        backupProperties.setEnvironment("PRODUCTION");
        backupProperties.setMinimumFreeBytes(1);
        DatabaseFileInspector inspector = new DatabaseFileInspector();
        DatabaseBackupEngine engine = new DatabaseBackupEngine(
                new SqliteOnlineBackup(dataSource), inspector, mapper, backupProperties,
                Clock.fixed(Instant.parse("2026-09-15T08:01:00Z"), ZoneOffset.UTC), "Asia/Shanghai",
                "jdbc:sqlite:" + source.toAbsolutePath(), keys, protectedData, null, recoveryKeys);

        DatabaseBackupMetadata metadata = engine.backup(isolatedBackup, "isolated-test-disk");
        Path latest = isolatedBackup.resolve("latest");
        assertThat(metadata.environment()).isEqualTo("PRODUCTION");
        assertThat(latest.resolve("platform.db")).isRegularFile();
        assertThat(latest.resolve("data-key.dpapi")).isRegularFile();
        assertThat(latest.resolve("factory-key-envelope.json")).isRegularFile();
        assertThat(formalRoot).doesNotExist();

        DatabaseImportService imports = mock(DatabaseImportService.class);
        DatabaseImportManifest manifest = new DatabaseImportManifest(
                "prepared.db", "prepared.data-key.dpapi", "key-hash", "db-hash");
        when(imports.registerExternal(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
            Path importedEnvelope = invocation.getArgument(1);
            assertThat(keys.loadEnvelope(importedEnvelope).keyId()).isEqualTo(storeKey.keyId());
            return new DatabaseBackupCandidate("candidate", "EXTERNAL", metadata.revision(), null,
                    metadata.generatedAt(), metadata.fileSize(), "PRODUCTION", "admin", 0, true);
        });
        when(imports.prepare(anyString())).thenReturn(manifest);
        DatabaseRecoveryService service = new DatabaseRecoveryService(recoveryKeys, engine, inspector, imports,
                keys, protector, mapper, Clock.fixed(Instant.parse("2026-09-15T08:02:00Z"), ZoneOffset.UTC));

        DatabaseRecoveryRequest request = service.createRequest(latest);
        DatabaseRecoveryEnvelope factoryEnvelope = mapper.readValue(
                latest.resolve("factory-key-envelope.json").toFile(), DatabaseRecoveryEnvelope.class);
        byte[] recovered = decrypt(vendorPair.getPrivate(), factoryEnvelope.wrappedKey());
        assertThat(DataProtectionKeyManager.material(recovered).keyId()).isEqualTo(request.keyId());
        byte[] responseWrapped = encrypt(request.temporaryPublicKey(), recovered);
        DatabaseRecoveryResponse response = new DatabaseRecoveryResponse(
                DatabaseRecoveryResponse.FORMAT, request.requestId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(responseWrapped), request.expiresAt());
        Path responsePath = userData.resolve("recovery-response.json");
        Files.writeString(responsePath, mapper.writeValueAsString(response));

        assertThat(service.importResponse(latest, responsePath)).isEqualTo(manifest);
        assertThatThrownBy(() -> service.importResponse(latest, responsePath))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("DATABASE_RECOVERY_REQUEST_NOT_FOUND"));

        DataProtectionKeyManager restartedKeys = new DataProtectionKeyManager(
                protectionProperties, protector, mapper, "jdbc:sqlite:" + source.toAbsolutePath());
        assertThat(restartedKeys.loadExisting().keyId()).isEqualTo(storeKey.keyId());
        assertThat(formalRoot).doesNotExist();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] decrypt(PrivateKey key, String wrapped) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, oaep());
        return cipher.doFinal(Base64.getUrlDecoder().decode(wrapped));
    }

    private static byte[] encrypt(String publicPem, byte[] raw) throws Exception {
        String body = publicPem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaep());
        return cipher.doFinal(raw);
    }

    private static OAEPParameterSpec oaep() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
