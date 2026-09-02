package com.ledgame.platform;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseStateService {
    private final JdbcTemplate jdbc;

    public DatabaseStateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DatabaseStateSnapshot current() {
        return jdbc.queryForObject("""
            SELECT instance_id, revision, last_business_modified_at,
                   imported_from_revision, imported_at
              FROM database_state WHERE id=1
            """, (resultSet, rowNumber) -> snapshot(resultSet));
    }

    public static DatabaseStateSnapshot read(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                 SELECT instance_id, revision, last_business_modified_at,
                        imported_from_revision, imported_at
                   FROM database_state WHERE id=1
                 """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) return null;
            return snapshot(resultSet);
        }
    }

    private static DatabaseStateSnapshot snapshot(ResultSet resultSet) throws SQLException {
        long importedRevision = resultSet.getLong("imported_from_revision");
        String importedAt = resultSet.getString("imported_at");
        return new DatabaseStateSnapshot(
                resultSet.getString("instance_id"),
                resultSet.getLong("revision"),
                Instant.parse(resultSet.getString("last_business_modified_at")),
                resultSet.wasNull() ? null : importedRevision,
                importedAt == null ? null : Instant.parse(importedAt));
    }
}
