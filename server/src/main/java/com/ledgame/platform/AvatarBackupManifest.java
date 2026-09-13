package com.ledgame.platform;

import java.util.List;

public record AvatarBackupManifest(String format, List<Entry> files) {
    public static final String FORMAT = "ledgame-avatar-backup-v1";
    public record Entry(String name, long size, String sha256) {}
}
