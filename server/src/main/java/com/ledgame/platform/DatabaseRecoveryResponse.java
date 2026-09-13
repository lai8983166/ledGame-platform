package com.ledgame.platform;

import java.time.Instant;

/** Minimal vendor response bound to one recovery request. */
public record DatabaseRecoveryResponse(
        String format,
        String requestId,
        String wrappedKey,
        Instant expiresAt) {
    public static final String FORMAT = "ledgame-database-recovery-response-v1";
}
