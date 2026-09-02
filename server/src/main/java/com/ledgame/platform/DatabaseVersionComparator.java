package com.ledgame.platform;

public final class DatabaseVersionComparator {
    private DatabaseVersionComparator() {}

    public static BackupSyncRelation compare(DatabaseStateSnapshot source, DatabaseStateSnapshot backup) {
        if (source == null || backup == null || source.instanceId() == null || backup.instanceId() == null) {
            return BackupSyncRelation.VERSION_UNAVAILABLE;
        }
        if (!source.instanceId().equals(backup.instanceId())) return BackupSyncRelation.IDENTITY_CONFLICT;
        if (source.revision() == backup.revision()) return BackupSyncRelation.SYNCHRONIZED;
        return source.revision() > backup.revision()
                ? BackupSyncRelation.BACKUP_BEHIND
                : BackupSyncRelation.BACKUP_AHEAD;
    }
}
