package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ChildModeServiceTest {
    @Test
    void defaultsOffAndPersistsAcrossServiceInstances() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE store_feature_settings (id INTEGER PRIMARY KEY, child_mode INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        ChildModeService first = new ChildModeService(jdbc);

        assertThat(first.enabled()).isFalse();
        assertThat(first.update(true)).isTrue();
        assertThat(new ChildModeService(jdbc).enabled()).isTrue();
        dataSource.destroy();
    }
}
