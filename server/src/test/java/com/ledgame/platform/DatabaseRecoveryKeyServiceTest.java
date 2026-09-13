package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseRecoveryKeyServiceTest {
    @TempDir Path root;

    @Test
    void wrapsOnlyTheStoreKeyWithConfiguredVendorPublicKey() throws Exception {
        KeyPair pair = rsa();
        Path publicKey = root.resolve("recovery-public.pem");
        Files.writeString(publicKey, pem(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        DatabaseRecoveryProperties properties = new DatabaseRecoveryProperties();
        properties.setPublicKeyPath(publicKey.toString());
        DatabaseRecoveryKeyService service = new DatabaseRecoveryKeyService(properties, new ObjectMapper());
        byte[] raw = new byte[32];
        for (int index = 0; index < raw.length; index++) raw[index] = (byte) index;
        DataKeyMaterial material = DataProtectionKeyManager.material(raw);

        DatabaseRecoveryEnvelope envelope = service.wrap(material);

        assertThat(envelope.format()).isEqualTo(DatabaseRecoveryEnvelope.FORMAT);
        assertThat(envelope.algorithm()).isEqualTo(DatabaseRecoveryEnvelope.ALGORITHM);
        assertThat(envelope.recoveryKeyId()).isEqualTo("factory-recovery-v1");
        assertThat(envelope.keyId()).isEqualTo(material.keyId());
        assertThat(envelope.wrappedKey()).doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate(), oaepSpec());
        assertThat(cipher.doFinal(Base64.getUrlDecoder().decode(envelope.wrappedKey()))).containsExactly(raw);
    }

    @Test
    void rejectsUnknownOrMalformedEnvelope() {
        DatabaseRecoveryProperties properties = new DatabaseRecoveryProperties();
        DatabaseRecoveryKeyService service = new DatabaseRecoveryKeyService(properties, new ObjectMapper());
        assertThatThrownBy(() -> service.validateEnvelope(new DatabaseRecoveryEnvelope(
                "unknown", DatabaseRecoveryEnvelope.ALGORITHM, "factory-recovery-v1", "id", "AQ")))
                .hasMessage("DATABASE_RECOVERY_ENVELOPE_INVALID");
        assertThatThrownBy(() -> service.validateEnvelope(new DatabaseRecoveryEnvelope(
                DatabaseRecoveryEnvelope.FORMAT, DatabaseRecoveryEnvelope.ALGORITHM,
                "factory-recovery-v1", "id", "not base64 !!!")))
                .hasMessage("DATABASE_RECOVERY_ENVELOPE_INVALID");
    }

    @Test
    void oaepRejectsTamperedWrappedKey() throws Exception {
        KeyPair pair = rsa();
        byte[] ciphertext = new byte[256];
        ciphertext[0] = 1;
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate(), oaepSpec());

        assertThatThrownBy(() -> cipher.doFinal(ciphertext))
                .isInstanceOfAny(javax.crypto.BadPaddingException.class, javax.crypto.IllegalBlockSizeException.class);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
    }
}
