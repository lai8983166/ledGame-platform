package com.ledgame.platform;

import java.util.Arrays;

public record DataKeyMaterial(byte[] key, String keyId) {
    public DataKeyMaterial {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Data key must contain 32 bytes");
        key = Arrays.copyOf(key, key.length);
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("Data key id is required");
    }

    @Override public byte[] key() { return Arrays.copyOf(key, key.length); }
}
