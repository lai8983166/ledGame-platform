package com.ledgame.platform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;

final class ActivationCode {
    static final int MAX_LENGTH = 16384;
    record License(String code, String licenseId, String issuedAt) {}
    static PlatformApiException error(String code, String message) {
        return new PlatformApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    static String normalize(String input) {
        if (input == null || input.length() > MAX_LENGTH) throw error("ACTIVATION_CODE_INVALID", "激活码为空或过长，请重新复制完整激活码");
        String code = input.replaceAll("[ \\t\\r\\n]", "");
        if (code.isEmpty()) throw error("ACTIVATION_CODE_EMPTY", "请输入厂家提供的激活码");
        return code;
    }
    static License verify(String input, PublicKey key, String machineCode) {
        String code = normalize(input);
        String[] parts = code.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("LGACT1")) throw error("ACTIVATION_FORMAT_INVALID", "激活码格式不支持，请复制完整激活码");
        try {
            byte[] payload = decode(parts[1]), bytes = decode(parts[2]);
            var signature = Signature.getInstance("Ed25519");
            signature.initVerify(key); signature.update(payload);
            if (bytes.length != 64 || !signature.verify(bytes)) throw error("ACTIVATION_SIGNATURE_INVALID", "激活码签名无效，请联系厂家确认");
            var node = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(payload);
            if (!node.isObject() || !node.path("formatVersion").isIntegralNumber() || node.path("formatVersion").asInt() != 1)
                throw error("ACTIVATION_FORMAT_INVALID", "激活码版本不支持");
            if (!"ledgame-member-admin".equals(node.path("product").asText())) throw error("ACTIVATION_PRODUCT_MISMATCH", "激活码不适用于会员管理端");
            if (!machineCode.equals(node.path("machineCode").asText())) throw error("ACTIVATION_MACHINE_MISMATCH", "激活码不属于本机，请将本机机器码发给厂家");
            String id = node.path("licenseId").asText(""), issuedAt = node.path("issuedAt").asText("");
            if (!node.path("licenseId").isTextual() || !node.path("issuedAt").isTextual()
                    || id.isBlank() || id.length() > 128 || node.has("expiresAt")) throw error("ACTIVATION_FORMAT_INVALID", "授权信息格式不正确");
            Instant.parse(issuedAt);
            return new License(code, id, issuedAt);
        } catch (PlatformApiException e) { throw e; }
        catch (Exception e) { throw error("ACTIVATION_FORMAT_INVALID", "激活码内容不正确，请重新复制完整激活码"); }
    }
    private static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw new IllegalArgumentException();
        return bytes;
    }
}
