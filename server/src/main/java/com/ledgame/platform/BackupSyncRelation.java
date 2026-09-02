package com.ledgame.platform;

public enum BackupSyncRelation {
    SYNCHRONIZED,
    BACKUP_BEHIND,
    BACKUP_AHEAD,
    IDENTITY_CONFLICT,
    VERSION_UNAVAILABLE
}
