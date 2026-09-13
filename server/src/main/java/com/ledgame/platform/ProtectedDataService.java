package com.ledgame.platform;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProtectedDataService {
    public static final String ENCRYPTION_VERSION = "v1";
    private static final String PREFIX = "enc:" + ENCRYPTION_VERSION + ":";
    private final DataProtectionKeyManager keys;
    private final DataKeyMaterial fixedKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ProtectedDataService(DataProtectionKeyManager keys) {
        this.keys = keys;
        this.fixedKey = null;
    }

    private ProtectedDataService(DataKeyMaterial fixedKey) {
        this.keys = null;
        this.fixedKey = fixedKey;
    }

    static ProtectedDataService forKey(DataKeyMaterial key) {
        return new ProtectedDataService(key);
    }

    public String encrypt(String context, String plaintext) {
        if (plaintext == null) return null;
        if (isEncrypted(plaintext)) return plaintext;
        DataKeyMaterial material = key(true);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(material.key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + material.keyId() + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("DATA_PROTECTION_ENCRYPT_FAILED", exception);
        }
    }

    public String encryptField(String table, String column, String plaintext) {
        return encrypt(table + "." + column, plaintext);
    }

    public String decrypt(String context, Object rawValue) {
        if (rawValue == null) return null;
        String value = String.valueOf(rawValue);
        if (value.startsWith("enc:") && !isEncrypted(value)) throw invalid();
        if (!isEncrypted(value)) return value;
        String[] parts = value.split(":", 5);
        if (parts.length != 5 || !ENCRYPTION_VERSION.equals(parts[1])) throw invalid();
        DataKeyMaterial material = key(false);
        if (!material.keyId().equals(parts[2])) throw new IllegalStateException("DATA_PROTECTION_KEY_ID_MISMATCH");
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[3]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[4]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material.key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw invalid(); }
    }

    public String decryptField(String table, String column, Object storedValue) {
        return decrypt(table + "." + column, storedValue);
    }

    /** Encrypts binary payloads (such as uploaded avatars) with the same key
     * material and authenticated context used for protected database fields. */
    public byte[] encryptBytes(String context, byte[] plaintext) {
        if (plaintext == null) return null;
        DataKeyMaterial material = key(true);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(material.key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("ledgame-bytes-" + ENCRYPTION_VERSION);
                output.writeUTF(material.keyId());
                output.writeInt(nonce.length);
                output.write(nonce);
                output.writeInt(ciphertext.length);
                output.write(ciphertext);
            }
            return bytes.toByteArray();
        } catch (GeneralSecurityException | java.io.IOException exception) {
            throw new IllegalStateException("DATA_PROTECTION_ENCRYPT_FAILED", exception);
        }
    }

    public byte[] decryptBytes(String context, byte[] envelope) {
        if (envelope == null) return null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (!("ledgame-bytes-" + ENCRYPTION_VERSION).equals(input.readUTF())) throw invalid();
            String envelopeKeyId = input.readUTF();
            DataKeyMaterial material = key(false);
            if (!material.keyId().equals(envelopeKeyId)) throw new IllegalStateException("DATA_PROTECTION_KEY_ID_MISMATCH");
            int nonceLength = input.readInt();
            if (nonceLength != 12) throw invalid();
            byte[] nonce = input.readNBytes(nonceLength);
            int ciphertextLength = input.readInt();
            if (ciphertextLength < 16 || ciphertextLength > 16 * 1024 * 1024) throw invalid();
            byte[] ciphertext = input.readNBytes(ciphertextLength);
            if (nonce.length != nonceLength || ciphertext.length != ciphertextLength || input.read() != -1) throw invalid();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material.key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public String lookupHash(String context, String normalizedValue) {
        if (normalizedValue == null) return null;
        try {
            DataKeyMaterial material = key(true);
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(material.key(), "HmacSHA256"));
            byte[] lookupKey = derivation.doFinal("ledgame-lookup-v1".getBytes(StandardCharsets.UTF_8));
            Mac lookup = Mac.getInstance("HmacSHA256");
            lookup.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
            lookup.update(context.getBytes(StandardCharsets.UTF_8));
            lookup.update((byte) 0);
            return HexFormat.of().formatHex(lookup.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("DATA_PROTECTION_LOOKUP_FAILED", exception);
        }
    }

    public String phoneLookupHash(String normalizedPhone) {
        return lookupHash("members.phone", normalizedPhone);
    }

    public String wristbandLookupHash(String normalizedUid) {
        return lookupHash("wristbands.card_uid", normalizedUid);
    }

    public String keyId() { return key(true).keyId(); }
    public boolean isEncrypted(String value) { return value != null && value.startsWith(PREFIX); }

    private static IllegalStateException invalid() {
        return new IllegalStateException("DATA_PROTECTION_INTEGRITY_FAILED");
    }

    private DataKeyMaterial key(boolean create) {
        if (fixedKey != null) return fixedKey;
        return create ? keys.loadOrCreate() : keys.loadExisting();
    }
}
