package com.ledgame.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record DatabaseSourceFingerprint(List<FilePart> parts) {
    public static DatabaseSourceFingerprint capture(Path database) {
        List<FilePart> parts = new ArrayList<>();
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path file = Path.of(database.toString() + suffix);
            try {
                parts.add(new FilePart(suffix, Files.exists(file),
                        Files.exists(file) ? Files.size(file) : 0,
                        Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0));
            } catch (Exception exception) {
                parts.add(new FilePart(suffix, Files.exists(file), -1, -1));
            }
        }
        return new DatabaseSourceFingerprint(List.copyOf(parts));
    }

    public record FilePart(String suffix, boolean exists, long size, long modifiedMillis) {}
}
