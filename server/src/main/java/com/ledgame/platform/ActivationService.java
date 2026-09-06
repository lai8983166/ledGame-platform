package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ActivationService {
    public record Status(boolean activated, String machineCode, String errorCode, String message, String licenseId) {}
    private final Path file;
    private final Supplier<String> identity;
    private final Supplier<PublicKey> key;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Status status = new Status(false, "", "ACTIVATION_REQUIRED", "会员管理端尚未激活，请向厂家申请激活码", "");
    private Runnable readyCallback;

    @Autowired
    public ActivationService(@Value("${ledgame.activation.directory:./activation}") String directory) {
        this(Path.of(directory), WindowsMachineIdentity::read, ActivationService::trustedKey);
    }
    ActivationService(Path directory, Supplier<String> identity, Supplier<PublicKey> key) {
        this.file = directory.resolve("license.json"); this.identity = identity; this.key = key;
        refresh();
    }
    public Status status() { return status; }
    public boolean activated() { return status.activated(); }
    public void requireActivated() {
        if (!activated()) throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_NOT_ACTIVATED", "会员管理端尚未激活，请在会员管理端完成厂家激活");
    }
    public synchronized void whenActivated(Runnable callback) {
        readyCallback = callback;
        notifyReady();
    }
    private void notifyReady() {
        if (activated() && readyCallback != null) { var callback = readyCallback; readyCallback = null; callback.run(); }
    }
    public synchronized Status refresh() {
        if (activated()) return status;
        String machine = "";
        try {
            machine = identity.get();
            PublicKey trusted = key.get();
            if (!Files.exists(file)) {
                status = new Status(false, machine, "ACTIVATION_REQUIRED", "请将机器码发给厂家，收到激活码后粘贴并点击激活", "");
            } else {
                if (Files.size(file) > ActivationCode.MAX_LENGTH + 256) throw ActivationCode.error("ACTIVATION_FILE_INVALID", "本机授权文件损坏，请重新粘贴厂家激活码");
                String code = mapper.readTree(Files.readAllBytes(file)).path("code").asText();
                var license = ActivationCode.verify(code, trusted, machine);
                status = new Status(true, machine, "", "本机已激活", license.licenseId());
                notifyReady();
            }
        } catch (PlatformApiException e) { status = new Status(false, machine, e.getCode(), e.getReason(), ""); }
        catch (Exception e) { status = new Status(false, machine, "ACTIVATION_FILE_UNREADABLE", "无法读取本机授权，请检查目录权限或重新输入激活码", ""); }
        return status;
    }
    public synchronized Status activate(String code) {
        String machine = identity.get();
        var license = ActivationCode.verify(code, key.get(), machine);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "license-", ".tmp");
            Files.write(temporary, mapper.writeValueAsBytes(java.util.Map.of("code", license.code())));
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw ActivationCode.error("ACTIVATION_SAVE_FAILED", "激活码有效，但授权保存失败，请检查本机数据目录权限后重试"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
        status = new Status(true, machine, "", "激活成功，正在检查数据备份", license.licenseId());
        notifyReady();
        return status;
    }
    private static PublicKey trustedKey() {
        try (var stream = ActivationService.class.getResourceAsStream("/activation-public.pem")) {
            if (stream == null) throw new IllegalStateException();
            String pem = new String(stream.readNBytes(4096), java.nio.charset.StandardCharsets.US_ASCII);
            String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) { throw ActivationCode.error("ACTIVATION_PUBLIC_KEY_MISSING", "程序未配置有效厂家公钥，请联系厂家提供完整安装包"); }
    }
}
