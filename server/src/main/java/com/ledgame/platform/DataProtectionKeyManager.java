package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DataProtectionKeyManager {
    public static final String ENVELOPE_FORMAT = "ledgame-data-key-v1";
    private final DataProtectionProperties properties;
    private final WindowsDataProtector protector;
    private final ObjectMapper objectMapper;
    private final Path keyPath;
    private DataKeyMaterial current;

    public DataProtectionKeyManager(DataProtectionProperties properties, WindowsDataProtector protector,
                                    ObjectMapper objectMapper,
                                    @Value("${spring.datasource.url}") String datasourceUrl) {
        this.properties = properties;
        this.protector = protector;
        this.objectMapper = objectMapper;
        this.keyPath = resolveKeyPath(properties.getKeyPath(), datasourceUrl);
    }

    public synchronized DataKeyMaterial loadOrCreate() {
        if (current != null) return current;
        String testKey = properties.getTestKeyBase64().trim();
        if (!testKey.isEmpty()) return current = material(Base64.getDecoder().decode(testKey));
        if (Files.isRegularFile(keyPath)) return current = readEnvelope(keyPath);
        try {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            DataKeyMaterial material = material(key);
            Files.createDirectories(keyPath.getParent());
            Path temporary = keyPath.resolveSibling(keyPath.getFileName() + ".writing");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    new KeyEnvelope(ENVELOPE_FORMAT, material.keyId(),
                            Base64.getEncoder().encodeToString(protector.protect(key))));
            try {
                Files.move(temporary, keyPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, keyPath);
            }
            return current = material;
        } catch (Exception exception) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_CREATE_FAILED", exception);
        }
    }

    public synchronized DataKeyMaterial loadExisting() {
        if (current != null) return current;
        String testKey = properties.getTestKeyBase64().trim();
        if (!testKey.isEmpty()) return current = material(Base64.getDecoder().decode(testKey));
        if (!Files.isRegularFile(keyPath)) throw new IllegalStateException("DATA_PROTECTION_KEY_MISSING");
        return current = readEnvelope(keyPath);
    }

    public Path keyPath() { return keyPath; }

    public boolean requiresProtectedEnvelope() {
        return properties.getTestKeyBase64().trim().isEmpty();
    }

    public DataKeyMaterial loadEnvelope(Path envelopePath) {
        if (!requiresProtectedEnvelope()) return loadExisting();
        if (!Files.isRegularFile(envelopePath)) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_MISSING");
        }
        return readEnvelope(envelopePath);
    }

    public byte[] envelopeBytes() {
        if (!Files.isRegularFile(keyPath)) return new byte[0];
        try { return Files.readAllBytes(keyPath); }
        catch (Exception exception) { throw new IllegalStateException("DATA_PROTECTION_KEY_READ_FAILED", exception); }
    }

    /** Installs a recovered store key under the current Windows user. */
    public synchronized void install(DataKeyMaterial material) {
        if (material == null || material.key().length != 32) {
            throw new IllegalArgumentException("DATA_PROTECTION_KEY_INVALID");
        }
        String configured = properties.getTestKeyBase64().trim();
        if (!configured.isEmpty() && !Arrays.equals(Base64.getDecoder().decode(configured), material.key())) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_TEST_MISMATCH");
        }
        writeEnvelope(keyPath, material);
        current = material;
    }

    /** Writes a DPAPI envelope to a staging path without changing the active key. */
    public void writeEnvelope(Path target, DataKeyMaterial material) {
        if (material == null || material.key().length != 32) {
            throw new IllegalArgumentException("DATA_PROTECTION_KEY_INVALID");
        }
        try {
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".writing");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    new KeyEnvelope(ENVELOPE_FORMAT, material.keyId(),
                            Base64.getEncoder().encodeToString(protector.protect(material.key()))));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("DATA_PROTECTION_KEY_WRITE_FAILED", exception);
        }
    }

    private DataKeyMaterial readEnvelope(Path path) {
        try {
            KeyEnvelope envelope = objectMapper.readValue(path.toFile(), KeyEnvelope.class);
            if (!ENVELOPE_FORMAT.equals(envelope.format())) throw new IllegalStateException("DATA_PROTECTION_KEY_FORMAT_INVALID");
            DataKeyMaterial material = material(protector.unprotect(Base64.getDecoder().decode(envelope.protectedKey())));
            if (!material.keyId().equals(envelope.keyId())) throw new IllegalStateException("DATA_PROTECTION_KEY_ID_MISMATCH");
            return material;
        } catch (IllegalStateException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("DATA_PROTECTION_KEY_READ_FAILED", exception); }
    }

    static Path resolveKeyPath(String configured, String datasourceUrl) {
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        Path database = DatabaseBackupEngine.databasePath(datasourceUrl);
        Path parent = database.getParent() == null ? Path.of(".").toAbsolutePath().normalize() : database.getParent();
        return parent.resolve("security").resolve("data-key.dpapi");
    }

    static DataKeyMaterial material(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return new DataKeyMaterial(key, HexFormat.of().formatHex(digest, 0, 12));
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    public record KeyEnvelope(String format, String keyId, String protectedKey) {}
}
