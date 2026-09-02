package com.ledgame.platform;

public enum BackupLifecycleState {
    CHECKING,
    READY_PROTECTED,
    READY_DEGRADED,
    MAINTENANCE_LOGIN_REQUIRED,
    IMPORTING,
    BLOCKED
}
