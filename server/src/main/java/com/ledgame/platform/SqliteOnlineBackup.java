package com.ledgame.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.sqlite.SQLiteConnection;
import org.springframework.stereotype.Service;

@Service
public class SqliteOnlineBackup {
    private final DataSource dataSource;

    public SqliteOnlineBackup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void create(Path destination) {
        try {
            Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
            Files.deleteIfExists(destination);
            try (var connection = dataSource.getConnection()) {
                SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
                int result = sqlite.getDatabase().backup("main", destination.toString(), null, 256, 25, 5000);
                if (result != 0) throw new IllegalStateException("SQLite backup returned code " + result);
            }
        } catch (Exception exception) {
            try { Files.deleteIfExists(destination); } catch (Exception ignored) {}
            throw new IllegalStateException(BackupErrorCode.ONLINE_BACKUP_FAILED.name(), exception);
        }
    }
}
