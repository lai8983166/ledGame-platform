package com.ledgame.platform;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MemberTimeAccountMigrationTest {
    private final Instant now = Instant.parse("2026-10-14T08:00:00Z");

    private JdbcTemplate oldDatabase(Path path) throws Exception {
        var source = new ClassPathResource("schema.sql");
        String sql = new String(source.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("    time_balance_seconds INTEGER NOT NULL DEFAULT 0 CHECK (time_balance_seconds >= 0),\n", "")
                .replace("    consumption_started_at TEXT,\n", "")
                .replace("    member_id INTEGER REFERENCES members(id),\n", "");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + path);
        try (var connection = ds.getConnection()) { ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        var jdbc = new JdbcTemplate(ds);
        for (int id=1; id<=2; id++) jdbc.update("INSERT INTO members(id,phone,name,status,created_at,updated_at,created_by) VALUES (?,?,'旧会员','ACTIVE',?,?,'test')", id, "1380013800"+id, now.toString(), now.toString());
        String[] statuses = {"READY", "ACTIVE", "RETURNED", "EXPIRED"};
        for (int i=0;i<4;i++) {
            jdbc.update("INSERT INTO wristbands(id,card_uid,status,created_at,updated_at) VALUES (?,?,'IN_STOCK',?,?)", i+1, "228305561"+i, now.toString(), now.toString());
            jdbc.update("INSERT INTO wristband_bindings(wristband_id,member_id,status,duration_minutes,bound_at,started_at) VALUES (?,1,?,?,?,?)", i+1, statuses[i], i==0?10:30, now.minusSeconds(500).toString(), now.minusSeconds(330).toString());
        }
        return jdbc;
    }

    @Test
    void migratesReadyAndLiveRemainingOnlyOnceAndPreservesHistory() throws Exception {
        Path path=Files.createTempFile("member-time-migration-", ".db");
        try {
            var jdbc=oldDatabase(path);
            var migration=new PlatformSchemaMigration(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=1", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(allocated_seconds) FROM wristband_bindings WHERE member_id=1", Long.class)).isEqualTo(2070);
            assertThat(jdbc.queryForObject("SELECT consumption_started_at FROM members WHERE id=1", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=2", Long.class)).isZero();
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=1", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(allocated_seconds) FROM wristband_bindings WHERE member_id=1", Long.class)).isEqualTo(2070);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wristband_bindings", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT name FROM members WHERE id=1", String.class)).isEqualTo("旧会员");
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void failedMigrationRollsBackBalancesAndMarkerSoRetryIsSafe() throws Exception {
        Path path=Files.createTempFile("member-time-rollback-", ".db");
        try {
            var jdbc=oldDatabase(path);
            jdbc.execute("CREATE TRIGGER reject_second_member BEFORE UPDATE ON members WHEN NEW.id=2 BEGIN SELECT RAISE(ABORT,'test failure'); END");
            var migration=new PlatformSchemaMigration(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            assertThatThrownBy(()->migration.run(new DefaultApplicationArguments(new String[0]))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForList("PRAGMA table_info(members)")).noneMatch(row->"time_balance_seconds".equals(row.get("name")));
            jdbc.execute("DROP TRIGGER reject_second_member");
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=1", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(allocated_seconds) FROM wristband_bindings WHERE member_id=1", Long.class)).isEqualTo(2070);
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void addsAdjustmentTableWithoutChangingAccountsAndTracksBackupRevision() throws Exception {
        Path path=Files.createTempFile("time-adjustment-migration-", ".db");
        try {
            var jdbc=oldDatabase(path);
            var migration=new PlatformSchemaMigration(jdbc,Clock.fixed(now,ZoneOffset.UTC));
            migration.run(new DefaultApplicationArguments(new String[0]));
            long before=jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=2",Long.class);
            long revision=jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1",Long.class);
            jdbc.update("INSERT INTO member_time_adjustments(member_id,request_id,delta_seconds,before_seconds,after_seconds,reason,adjusted_at,operator_id,operator_username,operator_display_name) VALUES (2,'migration-test',60,0,60,'测试',?,1,'test','测试操作员')",now.toString());
            assertThat(jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1",Long.class)).isEqualTo(revision+1);
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_time_adjustments",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=2",Long.class)).isEqualTo(before);
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void levelConfigurationMigrationIsEmptyRepeatableAndTrackedForBackup() throws Exception {
        Path path = Files.createTempFile("member-level-migration-", ".db");
        try {
            var jdbc = oldDatabase(path);
            var migration = new PlatformSchemaMigration(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            migration.run(new DefaultApplicationArguments(new String[0]));
            var members = jdbc.queryForList("SELECT * FROM members ORDER BY id");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_levels", Integer.class)).isZero();
            long revision = jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class);
            jdbc.update("INSERT INTO member_levels(name,min_points,max_points,created_at,updated_at) VALUES ('等级测试',0,999,?,?)", now.toString(), now.toString());
            assertThat(jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class)).isEqualTo(revision + 1);
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForList("SELECT * FROM members ORDER BY id")).isEqualTo(members);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_levels", Integer.class)).isEqualTo(1);
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void pointsLedgerIsRepeatableTrackedAndLeavesExistingAccountsUntouched() throws Exception {
        Path path = Files.createTempFile("member-points-migration-", ".db");
        try {
            var jdbc = oldDatabase(path);
            var migration = new PlatformSchemaMigration(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            migration.run(new DefaultApplicationArguments(new String[0]));
            var members = jdbc.queryForList("SELECT * FROM members ORDER BY id");
            long revision = jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class);
            jdbc.update("""
                INSERT INTO member_point_deductions(member_id,request_id,points_deducted,before_points,after_points,
                    ranking_points,reason,deducted_at,operator_id,operator_username,operator_display_name)
                VALUES (2,'points-migration',5,20,15,20,'测试兑换',?,1,'test','测试操作员')
                """, now.toString());
            assertThat(jdbc.queryForObject("SELECT revision FROM database_state WHERE id=1", Long.class)).isEqualTo(revision + 1);
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForList("SELECT * FROM members ORDER BY id")).isEqualTo(members);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_point_deductions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT after_points FROM member_point_deductions", Integer.class)).isEqualTo(15);
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void upgradesSharedAccountToAllocationAndKeepsUnboundAccountWithoutDoubleCredit() throws Exception {
        Path path = Files.createTempFile("allocated-account-upgrade-", ".db");
        try {
            var jdbc = oldDatabase(path);
            jdbc.execute("ALTER TABLE members ADD COLUMN time_balance_seconds INTEGER NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE members ADD COLUMN consumption_started_at TEXT");
            jdbc.update("UPDATE members SET time_balance_seconds=3600,consumption_started_at=? WHERE id=1", now.minusSeconds(600).toString());
            jdbc.update("UPDATE members SET time_balance_seconds=1234 WHERE id=2");
            var migration = new PlatformSchemaMigration(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForList("PRAGMA table_info(wristband_charge_records)")).anyMatch(row->"precharge_request_id".equals(row.get("name")));
            assertThat(jdbc.queryForList("PRAGMA table_info(member_recharge_records)")).anyMatch(row->"source_charge_id".equals(row.get("name")));
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=1", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(allocated_seconds) FROM wristband_bindings WHERE member_id=1", Long.class)).isEqualTo(3000);
            assertThat(jdbc.queryForObject("SELECT time_balance_seconds FROM members WHERE id=2", Long.class)).isEqualTo(1234);
            migration.run(new DefaultApplicationArguments(new String[0]));
            assertThat(jdbc.queryForObject("SELECT SUM(allocated_seconds) FROM wristband_bindings WHERE member_id=1", Long.class)).isEqualTo(3000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_recharge_records", Integer.class)).isZero();
        } finally { Files.deleteIfExists(path); }
    }
}
