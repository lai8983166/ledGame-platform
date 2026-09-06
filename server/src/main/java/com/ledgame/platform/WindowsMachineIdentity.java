package com.ledgame.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class WindowsMachineIdentity {
    static String read() {
        Process process = null;
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) throw new IllegalStateException();
            process = new ProcessBuilder("reg.exe", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid", "/reg:64")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException();
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            var match = Pattern.compile("(?i)MachineGuid\\s+REG_SZ\\s+([0-9a-f-]{36})").matcher(output);
            if (!match.find()) throw new IllegalStateException();
            return machineCode(match.group(1));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ActivationCode.error("ACTIVATION_MACHINE_UNAVAILABLE", "无法读取本机机器码，请重试；仍失败请联系厂家检查 Windows 系统权限");
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    static String machineCode(String guid) {
        String value = guid == null ? "" : guid.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || value.replace("-", "").matches("0+"))
            throw ActivationCode.error("ACTIVATION_MACHINE_UNAVAILABLE", "机器身份无效，无法生成机器码");
        try { return "LGM1-" + HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("ledgame-member-admin|machine-v1|" + value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("机器码算法不可用"); }
    }
}
