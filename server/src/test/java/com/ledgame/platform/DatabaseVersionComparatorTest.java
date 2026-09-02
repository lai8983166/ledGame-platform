package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DatabaseVersionComparatorTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void comparesIdentityAndRevisionWithoutComparingDatabaseFileHashes() {
        DatabaseStateSnapshot current = state("store-a", 12);
        assertThat(DatabaseVersionComparator.compare(current, state("store-a", 12)))
                .isEqualTo(BackupSyncRelation.SYNCHRONIZED);
        assertThat(DatabaseVersionComparator.compare(current, state("store-a", 11)))
                .isEqualTo(BackupSyncRelation.BACKUP_BEHIND);
        assertThat(DatabaseVersionComparator.compare(current, state("store-a", 13)))
                .isEqualTo(BackupSyncRelation.BACKUP_AHEAD);
        assertThat(DatabaseVersionComparator.compare(current, state("store-b", 12)))
                .isEqualTo(BackupSyncRelation.IDENTITY_CONFLICT);
        assertThat(DatabaseVersionComparator.compare(current, null))
                .isEqualTo(BackupSyncRelation.VERSION_UNAVAILABLE);
    }

    private static DatabaseStateSnapshot state(String identity, long revision) {
        return new DatabaseStateSnapshot(identity, revision, NOW, null, null);
    }
}
