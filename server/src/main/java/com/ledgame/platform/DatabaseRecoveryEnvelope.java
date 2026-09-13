package com.ledgame.platform;

/** JSON contract written next to each production backup. */
public record DatabaseRecoveryEnvelope(
        String format,
        String algorithm,
        String recoveryKeyId,
        String keyId,
        String wrappedKey) {
    public static final String FORMAT = "ledgame-factory-recovery-key-v1";
    public static final String ALGORITHM = "RSA-OAEP-SHA256";
}
