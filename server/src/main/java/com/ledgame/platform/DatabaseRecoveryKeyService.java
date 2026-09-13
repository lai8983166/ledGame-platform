package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.stereotype.Service;

/** Wraps a per-store data key for the vendor recovery tool without exposing its private key. */
@Service
public class DatabaseRecoveryKeyService {
    private static final int MAX_PEM_BYTES = 16 * 1024;
    private final DatabaseRecoveryProperties properties;
    private final ObjectMapper objectMapper;

    public DatabaseRecoveryKeyService(DatabaseRecoveryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() { return properties.isEnabled(); }

    public String recoveryKeyId() { return properties.getRecoveryKeyId(); }

    public DatabaseRecoveryEnvelope wrap(DataKeyMaterial material) {
        if (!properties.isEnabled()) return null;
        if (material == null) throw new IllegalArgumentException("data key is required");
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey(), oaepSpec());
            byte[] wrapped = cipher.doFinal(material.key());
            return new DatabaseRecoveryEnvelope(
                    DatabaseRecoveryEnvelope.FORMAT,
                    DatabaseRecoveryEnvelope.ALGORITHM,
                    properties.getRecoveryKeyId(),
                    material.keyId(),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped));
        } catch (Exception exception) {
            throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_CREATE_FAILED", exception);
        }
    }

    public byte[] serialize(DatabaseRecoveryEnvelope envelope) {
        if (envelope == null) return new byte[0];
        try {
            return (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_SERIALIZE_FAILED", exception);
        }
    }

    public DatabaseRecoveryEnvelope read(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_PEM_BYTES) {
                throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_MISSING");
            }
            DatabaseRecoveryEnvelope envelope = objectMapper.readValue(path.toFile(), DatabaseRecoveryEnvelope.class);
            validateEnvelope(envelope);
            return envelope;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_INVALID", exception);
        }
    }

    public void validateEnvelope(DatabaseRecoveryEnvelope envelope) {
        if (envelope == null
                || !DatabaseRecoveryEnvelope.FORMAT.equals(envelope.format())
                || !DatabaseRecoveryEnvelope.ALGORITHM.equals(envelope.algorithm())
                || !properties.getRecoveryKeyId().equals(envelope.recoveryKeyId())
                || envelope.keyId() == null || envelope.keyId().isBlank()
                || envelope.wrappedKey() == null || envelope.wrappedKey().isBlank()) {
            throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_INVALID");
        }
        try {
            byte[] wrapped = Base64.getUrlDecoder().decode(envelope.wrappedKey());
            if (wrapped.length < 128 || wrapped.length > 1024) {
                throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_INVALID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DATABASE_RECOVERY_ENVELOPE_INVALID", exception);
        }
    }

    public PublicKey publicKey() {
        try {
            byte[] pem;
            String configured = properties.getPublicKeyPath();
            if (configured != null && !configured.isBlank()) {
                Path path = Path.of(configured).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path) || Files.size(path) > MAX_PEM_BYTES) {
                    throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_MISSING");
                }
                pem = Files.readAllBytes(path);
            } else {
                String resource = properties.getPublicKeyResource();
                try (InputStream stream = DatabaseRecoveryKeyService.class.getClassLoader()
                        .getResourceAsStream(resource)) {
                    if (stream == null) throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_MISSING");
                    pem = stream.readNBytes(MAX_PEM_BYTES + 1);
                    if (pem.length > MAX_PEM_BYTES) throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_INVALID");
                }
            }
            String text = new String(pem, StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            if (text.isBlank()) throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_INVALID");
            byte[] encoded = Base64.getDecoder().decode(text);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
            if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) {
                throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_INVALID");
            }
            return key;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("DATABASE_RECOVERY_PUBLIC_KEY_INVALID", exception);
        }
    }

    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
    }
}
