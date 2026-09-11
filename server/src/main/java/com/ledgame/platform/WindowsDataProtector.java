package com.ledgame.platform;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import org.springframework.stereotype.Component;

@Component
public class WindowsDataProtector {
    public byte[] protect(byte[] plaintext) {
        requireWindows();
        return Crypt32Util.cryptProtectData(plaintext);
    }

    public byte[] unprotect(byte[] ciphertext) {
        requireWindows();
        return Crypt32Util.cryptUnprotectData(ciphertext);
    }

    private static void requireWindows() {
        if (!Platform.isWindows()) throw new IllegalStateException("DATA_PROTECTION_WINDOWS_REQUIRED");
    }
}
