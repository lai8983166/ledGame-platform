package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class BackupTargetStateStore {
    private final Path path;
    private final ObjectMapper objectMapper;

    public BackupTargetStateStore(Path sourceDatabase, ObjectMapper objectMapper) {
        this.path = sourceDatabase.toAbsolutePath().normalize().getParent().resolve("backup-target-state.json");
        this.objectMapper = objectMapper;
    }

    public String readPreferredIdentity() {
        try {
            return objectMapper.readTree(path.toFile()).path("physicalIdentity").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void write(String physicalIdentity) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), Map.of("physicalIdentity", physicalIdentity));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // Target preference is an optimization; backup correctness does not depend on it.
        }
    }
}
