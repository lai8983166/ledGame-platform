package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivationCodeTest {
    private String sign(java.security.KeyPair keys, Map<String, Object> fields) throws Exception {
        byte[] payload = new ObjectMapper().writeValueAsBytes(fields);
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate()); signature.update(payload);
        return "LGACT1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }
    @Test void rejectsSignedWrongProductVersionAndAlteredPayload() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var fields = new java.util.HashMap<String, Object>(Map.of("formatVersion", 1,
                "product", "ledgame-member-admin", "machineCode", "machine",
                "licenseId", "test", "issuedAt", "1900-01-01T00:00:00Z"));
        String valid = sign(keys, fields);
        assertNotNull(ActivationCode.verify(valid, keys.getPublic(), "machine"));
        fields.put("product", "another-product");
        String wrongProduct = sign(keys, fields);
        assertEquals("ACTIVATION_PRODUCT_MISMATCH", assertThrows(PlatformApiException.class,
                () -> ActivationCode.verify(wrongProduct, keys.getPublic(), "machine")).getCode());
        fields.put("product", "ledgame-member-admin"); fields.put("formatVersion", 2);
        String wrongVersion = sign(keys, fields);
        assertEquals("ACTIVATION_FORMAT_INVALID", assertThrows(PlatformApiException.class,
                () -> ActivationCode.verify(wrongVersion, keys.getPublic(), "machine")).getCode());
        String altered = wrongProduct.substring(0, wrongProduct.lastIndexOf('.') + 1) + valid.substring(valid.lastIndexOf('.') + 1);
        assertEquals("ACTIVATION_SIGNATURE_INVALID", assertThrows(PlatformApiException.class,
                () -> ActivationCode.verify(altered, keys.getPublic(), "machine")).getCode());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void nodeFactoryCodeVerifiesInJava() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var file = directory.resolve("test-private.pem");
        java.nio.file.Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        String machine = WindowsMachineIdentity.machineCode("12345678-1234-1234-1234-123456789abc");
        var tool = java.nio.file.Path.of("..", "scripts", "activation-tool.mjs").toAbsolutePath();
        var process = new ProcessBuilder("node", tool.toString(), "issue", file.toString(), machine).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        String code = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertNotNull(ActivationCode.verify(code, keys.getPublic(), machine).licenseId());
    }
    @Test void signedCodeRoundTripAndWhitespace() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String machine = WindowsMachineIdentity.machineCode("12345678-1234-1234-1234-123456789abc");
        byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of("formatVersion", 1,
                "product", "ledgame-member-admin", "machineCode", machine,
                "licenseId", "TEST-001", "issuedAt", "2099-01-01T00:00:00Z"));
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate()); signature.update(payload);
        String code = "LGACT1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        assertEquals("TEST-001", ActivationCode.verify(" \t" + code + "\r\n", keys.getPublic(), machine).licenseId());
        assertThrows(PlatformApiException.class, () -> ActivationCode.verify(code, keys.getPublic(), "other"));
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(PlatformApiException.class, () -> ActivationCode.verify(code, other.getPublic(), machine));
        assertThrows(PlatformApiException.class, () -> ActivationCode.verify(code.replace("LGACT1", "LGACT2"), keys.getPublic(), machine));
        assertThrows(PlatformApiException.class, () -> ActivationCode.verify(code + "!", keys.getPublic(), machine));
    }

    @Test void invalidInputAndIdentityAreRejected() {
        assertThrows(PlatformApiException.class, () -> ActivationCode.normalize(" \r\n"));
        assertThrows(PlatformApiException.class, () -> ActivationCode.normalize("a".repeat(16385)));
        assertThrows(PlatformApiException.class, () -> WindowsMachineIdentity.machineCode(""));
        assertThrows(PlatformApiException.class, () -> WindowsMachineIdentity.machineCode("00000000-0000-0000-0000-000000000000"));
        assertEquals(WindowsMachineIdentity.machineCode("12345678-1234-1234-1234-123456789abc"),
                WindowsMachineIdentity.machineCode(" 12345678-1234-1234-1234-123456789ABC "));
    }
}
