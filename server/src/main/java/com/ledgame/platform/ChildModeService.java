package com.ledgame.platform;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChildModeService {
    private final JdbcTemplate jdbc;

    public ChildModeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabled() {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE((SELECT child_mode FROM store_feature_settings WHERE id=1), 0)", Integer.class);
        return value != null && value != 0;
    }

    public boolean update(boolean enabled) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO store_feature_settings(id, child_mode, created_at, updated_at) VALUES (1, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET child_mode=excluded.child_mode, updated_at=excluded.updated_at
                """, enabled ? 1 : 0, now, now);
        return enabled;
    }
}
