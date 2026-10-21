package com.ledgame.platform;

import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformSchemaMigration implements ApplicationRunner {
    public static final int CURRENT_SCHEMA_VERSION = 7;
    static final List<String> REVISION_TRACKED_TABLES = List.of(
            "members", "wristbands", "wristband_charge_records", "wristband_bindings", "member_recharge_records",
            "game_play_records", "legacy_game_sessions", "room_settings", "store_feature_settings", "store_settings",
            "operator_accounts", "operator_action_logs");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PlatformSchemaMigration(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    @Autowired
    public PlatformSchemaMigration(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateOperatorAccounts();
        migrateStoreSettings();
        migrateChargeAuditFields();
        migrateMemberTimeAccounts();
        migrateAllocatedTime();
        List<Map<String, Object>> memberColumns = jdbc.queryForList("PRAGMA table_info(members)");
        boolean hasDeletedAt = memberColumns.stream()
                .anyMatch(column -> "deleted_at".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!memberColumns.isEmpty() && !hasDeletedAt) {
            jdbc.execute("ALTER TABLE members ADD COLUMN deleted_at TEXT");
        }

        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(game_play_records)");
        boolean hasScoringPolicy = columns.stream()
                .anyMatch(column -> "scoring_policy".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!columns.isEmpty() && !hasScoringPolicy) {
            jdbc.execute("ALTER TABLE game_play_records ADD COLUMN scoring_policy TEXT");
        }
        boolean hasParticipantIndex = columns.stream()
                .anyMatch(column -> "participant_index".equalsIgnoreCase(String.valueOf(column.get("name"))));
        boolean hasMultiplayerColumns = List.of("device_id", "external_session_id", "binding_id").stream()
                .allMatch(required -> columns.stream().anyMatch(
                        column -> required.equalsIgnoreCase(String.valueOf(column.get("name")))));
        if (hasMultiplayerColumns && !hasParticipantIndex) {
            jdbc.execute("ALTER TABLE game_play_records ADD COLUMN participant_index INTEGER NOT NULL DEFAULT 0");
        }
        if (hasMultiplayerColumns) {
            jdbc.execute("DROP INDEX IF EXISTS ux_game_play_external_session");
            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_binding
                    ON game_play_records(device_id, external_session_id, binding_id)
                """);
            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_participant
                    ON game_play_records(device_id, external_session_id, participant_index)
                """);
        }
        migrateLegacySettledPlayStatus();
        ensureLegacyGameSessionTable();
        ensureDatabaseStateAndRevisionTriggers();
        jdbc.execute("PRAGMA user_version=" + CURRENT_SCHEMA_VERSION);
    }

    private void migrateLegacySettledPlayStatus() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(game_play_records)");
        if (columns.isEmpty()) return;
        String createSql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='game_play_records'", String.class);
        if (createSql != null && createSql.contains("LEGACY_SETTLED")) return;
        List<String> required = List.of("id", "member_id", "binding_id", "wristband_uid", "device_id",
                "room_id", "external_session_id", "participant_index", "game_id", "game_name", "status",
                "started_at", "ended_at", "success", "termination_reason", "raw_score", "points_awarded",
                "scoring_policy", "result_json");
        if (!required.stream().allMatch(name -> columns.stream()
                .anyMatch(column -> name.equalsIgnoreCase(String.valueOf(column.get("name")))))) return;

        transactions.executeWithoutResult(status -> {
            String latestSql = jdbc.queryForObject(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='game_play_records'", String.class);
            if (latestSql != null && latestSql.contains("LEGACY_SETTLED")) return;
            jdbc.execute("ALTER TABLE game_play_records RENAME TO game_play_records_before_legacy_settled");
            jdbc.execute(createGamePlayRecordsTableSql());
            jdbc.execute("""
                INSERT INTO game_play_records(
                    id, member_id, binding_id, wristband_uid, device_id, room_id,
                    external_session_id, participant_index, game_id, game_name, status,
                    started_at, ended_at, success, termination_reason, raw_score,
                    points_awarded, scoring_policy, result_json)
                SELECT id, member_id, binding_id, wristband_uid, device_id, room_id,
                       external_session_id, participant_index, game_id, game_name, status,
                       started_at, ended_at, success, termination_reason, raw_score,
                       points_awarded, scoring_policy, result_json
                  FROM game_play_records_before_legacy_settled
                """);
            jdbc.execute("DROP TABLE game_play_records_before_legacy_settled");
            ensureGamePlayIndexes();
        });
    }

    private String createGamePlayRecordsTableSql() {
        return """
            CREATE TABLE game_play_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                member_id INTEGER NOT NULL REFERENCES members(id),
                binding_id INTEGER NOT NULL REFERENCES wristband_bindings(id),
                wristband_uid TEXT NOT NULL,
                device_id TEXT NOT NULL,
                room_id TEXT,
                external_session_id TEXT NOT NULL,
                participant_index INTEGER NOT NULL DEFAULT 0,
                game_id TEXT NOT NULL,
                game_name TEXT NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'ABORTED', 'LEGACY_SETTLED')),
                started_at TEXT NOT NULL,
                ended_at TEXT,
                success INTEGER,
                termination_reason TEXT,
                raw_score INTEGER,
                points_awarded INTEGER NOT NULL DEFAULT 0,
                scoring_policy TEXT,
                result_json TEXT)
            """;
    }

    private void ensureGamePlayIndexes() {
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_running_play_binding ON game_play_records(binding_id) WHERE status='RUNNING'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_game_plays_member_started ON game_play_records(member_id, started_at DESC)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_binding ON game_play_records(device_id, external_session_id, binding_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_game_play_session_participant ON game_play_records(device_id, external_session_id, participant_index)");
    }

    private void ensureLegacyGameSessionTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS legacy_game_sessions (
                session_id TEXT PRIMARY KEY,
                room_ip TEXT NOT NULL,
                game_id TEXT NOT NULL,
                game_name TEXT NOT NULL,
                participants_hash TEXT NOT NULL,
                is_admin INTEGER NOT NULL DEFAULT 0 CHECK (is_admin IN (0, 1)),
                status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SETTLED')),
                started_at TEXT NOT NULL,
                ended_at TEXT,
                settlement_fingerprint TEXT)
            """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_legacy_session_open_room ON legacy_game_sessions(room_ip) WHERE status='RUNNING'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_legacy_session_room_started ON legacy_game_sessions(room_ip, started_at DESC)");
    }

    private void migrateStoreSettings() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                app_title TEXT,
                app_icon_path TEXT,
                app_icon_sha256 TEXT,
                unit_price_cents INTEGER NOT NULL DEFAULT 100 CHECK (unit_price_cents > 0),
                secondary_display_enabled INTEGER NOT NULL DEFAULT 0 CHECK (secondary_display_enabled IN (0, 1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL)
            """);
        addColumnIfMissing("store_settings", "app_title", "TEXT");
        addColumnIfMissing("store_settings", "app_icon_path", "TEXT");
        addColumnIfMissing("store_settings", "app_icon_sha256", "TEXT");
        addColumnIfMissing("store_settings", "unit_price_cents", "INTEGER NOT NULL DEFAULT 100");
        addColumnIfMissing("store_settings", "secondary_display_enabled", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("store_settings", "created_at", "TEXT");
        addColumnIfMissing("store_settings", "updated_at", "TEXT");
        String now = java.time.Instant.now().toString();
        jdbc.update("""
            INSERT OR IGNORE INTO store_settings(
                id, app_title, app_icon_path, app_icon_sha256,
                unit_price_cents, secondary_display_enabled, created_at, updated_at)
            VALUES (1, NULL, NULL, NULL, 100, 0, ?, ?)
            """, now, now);
        jdbc.update("UPDATE store_settings SET unit_price_cents=100 WHERE unit_price_cents IS NULL OR unit_price_cents <= 0");
        jdbc.update("UPDATE store_settings SET secondary_display_enabled=0 WHERE secondary_display_enabled IS NULL");
    }

    private void migrateChargeAuditFields() {
        if (!tableExists("wristband_charge_records")) return;
        addColumnIfMissing("wristband_charge_records", "operator_id", "INTEGER");
        addColumnIfMissing("wristband_charge_records", "operator_username", "TEXT");
        addColumnIfMissing("wristband_charge_records", "operator_display_name", "TEXT");
        addColumnIfMissing("wristband_charge_records", "issued_at", "TEXT");
        addColumnIfMissing("wristband_charge_records", "member_id", "INTEGER REFERENCES members(id)");
    }

    private void migrateMemberTimeAccounts() {
        if (!tableExists("members")) return;
        transactions.executeWithoutResult(transaction -> {
            boolean migrated = jdbc.queryForList("PRAGMA table_info(members)").stream()
                    .anyMatch(row -> "time_balance_seconds".equals(row.get("name")));
            addColumnIfMissing("members", "time_balance_seconds", "INTEGER NOT NULL DEFAULT 0 CHECK (time_balance_seconds >= 0)");
            addColumnIfMissing("members", "consumption_started_at", "TEXT");
            if (!migrated && tableExists("wristband_bindings")) {
                Instant now = clock.instant();
                for (Map<String, Object> member : jdbc.queryForList("SELECT id FROM members")) {
                    long seconds = 0;
                    boolean consuming = false;
                    for (Map<String, Object> binding : jdbc.queryForList("SELECT status, duration_minutes, started_at FROM wristband_bindings WHERE member_id=? AND status IN ('READY','ACTIVE')", member.get("id"))) {
                        long remaining = ((Number) binding.get("duration_minutes")).longValue() * 60L;
                        if ("ACTIVE".equals(binding.get("status"))) {
                            remaining = Math.max(0, Duration.between(now, Instant.parse(String.valueOf(binding.get("started_at"))).plusSeconds(remaining)).getSeconds());
                            consuming |= remaining > 0;
                        }
                        seconds = Math.addExact(seconds, remaining);
                    }
                    jdbc.update("UPDATE members SET time_balance_seconds=?, consumption_started_at=? WHERE id=?", seconds,
                            consuming ? now.toString() : null, member.get("id"));
                }
            }
            if (tableExists("wristband_bindings")) {
                addColumnIfMissing("wristband_bindings", "issue_request_id", "TEXT");
                addColumnIfMissing("wristband_bindings", "issued_credit_minutes", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing("wristband_bindings", "issued_by_operator_id", "INTEGER");
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_binding_issue_request ON wristband_bindings(issue_request_id) WHERE issue_request_id IS NOT NULL");
                if (!migrated && tableExists("wristband_charge_records")) {
                    jdbc.update("""
                        UPDATE wristband_charge_records SET member_id=(
                          SELECT b.member_id FROM wristband_bindings b
                           WHERE b.wristband_id=wristband_charge_records.wristband_id
                             AND b.bound_at>=wristband_charge_records.charged_at
                           ORDER BY b.bound_at,b.id LIMIT 1)
                        WHERE member_id IS NULL
                        """);
                }
            }
        });
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }

    private void migrateAllocatedTime() {
        if (!tableExists("members") || !tableExists("wristband_bindings")) return;
        transactions.executeWithoutResult(transaction -> {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS member_recharge_records (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL REFERENCES members(id),
                  duration_minutes INTEGER NOT NULL CHECK(duration_minutes>0), amount_cents INTEGER,
                  request_id TEXT NOT NULL UNIQUE, charged_at TEXT NOT NULL,
                  operator_id INTEGER NOT NULL, operator_username TEXT NOT NULL, operator_display_name TEXT NOT NULL)
                """);
            boolean migrated = jdbc.queryForList("PRAGMA table_info(wristband_bindings)").stream()
                    .anyMatch(row -> "allocated_seconds".equals(row.get("name")));
            addColumnIfMissing("wristband_bindings", "allocated_seconds", "INTEGER NOT NULL DEFAULT 0 CHECK(allocated_seconds>=0)");
            addColumnIfMissing("wristband_bindings", "returned_seconds", "INTEGER NOT NULL DEFAULT 0 CHECK(returned_seconds>=0)");
            if (migrated) return;
            if (jdbc.queryForList("PRAGMA table_info(wristband_bindings)").stream().noneMatch(row -> "wristband_id".equals(row.get("name")))) return;
            Instant now = clock.instant();
            for (Map<String,Object> member : jdbc.queryForList("SELECT id,time_balance_seconds,consumption_started_at FROM members")) {
                long available = ((Number) member.get("time_balance_seconds")).longValue();
                if (member.get("consumption_started_at") != null) {
                    available = Math.max(0, Duration.between(now, Instant.parse(String.valueOf(member.get("consumption_started_at"))).plusSeconds(available)).getSeconds());
                }
                List<Map<String,Object>> bindings = jdbc.queryForList("SELECT id,wristband_id,duration_minutes FROM wristband_bindings WHERE member_id=? AND status IN ('READY','ACTIVE') ORDER BY id", member.get("id"));
                for (int index=0; index<bindings.size(); index++) {
                    Map<String,Object> binding = bindings.get(index);
                    // The old shared balance has no per-card split. Preserve all actual remaining rights.
                    long seconds = index == bindings.size()-1 ? available : Math.min(available, ((Number) binding.get("duration_minutes")).longValue()*60L);
                    available -= seconds;
                    String status = seconds > 0 ? "ACTIVE" : "EXPIRED";
                    jdbc.update("UPDATE wristband_bindings SET allocated_seconds=?,started_at=?,status=? WHERE id=?", seconds, now.toString(), status, binding.get("id"));
                    jdbc.update("UPDATE wristbands SET status=? WHERE id=?", status, binding.get("wristband_id"));
                }
                jdbc.update("UPDATE members SET time_balance_seconds=?,consumption_started_at=NULL WHERE id=?", available, member.get("id"));
            }
        });
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean present = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
        if (!present) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void migrateOperatorAccounts() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(operator_accounts)");
        if (columns.isEmpty()) return;
        boolean hasDeletedAt = columns.stream()
                .anyMatch(column -> "deleted_at".equalsIgnoreCase(String.valueOf(column.get("name"))));
        String createSql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='operator_accounts'", String.class);
        boolean oldRoleConstraint = createSql != null && createSql.contains("'OPERATOR'");
        if (!oldRoleConstraint) {
            if (!hasDeletedAt) jdbc.execute("ALTER TABLE operator_accounts ADD COLUMN deleted_at TEXT");
            return;
        }

        jdbc.execute("PRAGMA foreign_keys=OFF");
        try {
            jdbc.execute("""
                CREATE TABLE operator_accounts_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    display_name TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    account_type TEXT NOT NULL CHECK (account_type IN ('FACTORY_ADMIN', 'STORE_MANAGER', 'CLERK')),
                    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                    deleted_at TEXT,
                    created_by_operator_id INTEGER REFERENCES operator_accounts_v3(id),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL)
                """);
            String deletedAtExpression = hasDeletedAt ? "deleted_at" : "NULL";
            jdbc.execute("""
                INSERT INTO operator_accounts_v3(
                    id, username, display_name, password_hash, account_type, enabled,
                    deleted_at, created_by_operator_id, created_at, updated_at)
                SELECT id, username, display_name, password_hash,
                       CASE account_type WHEN 'OPERATOR' THEN 'CLERK' ELSE account_type END,
                       enabled, %s, created_by_operator_id, created_at, updated_at
                  FROM operator_accounts
                """.formatted(deletedAtExpression));
            jdbc.execute("DROP TABLE operator_accounts");
            jdbc.execute("ALTER TABLE operator_accounts_v3 RENAME TO operator_accounts");
        } finally {
            jdbc.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void ensureDatabaseStateAndRevisionTriggers() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS database_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                instance_id TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                last_business_modified_at TEXT NOT NULL,
                imported_from_revision INTEGER,
                imported_at TEXT)
            """);
        jdbc.update("""
            INSERT OR IGNORE INTO database_state(
                id, instance_id, revision, last_business_modified_at,
                imported_from_revision, imported_at)
            VALUES (1, lower(hex(randomblob(16))), 0,
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), NULL, NULL)
            """);
        for (String table : REVISION_TRACKED_TABLES) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            createRevisionTrigger(table, "insert", "INSERT");
            createRevisionTrigger(table, "update", "UPDATE");
            createRevisionTrigger(table, "delete", "DELETE");
        }
    }

    private void createRevisionTrigger(String table, String suffix, String operation) {
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS backup_revision_" + table + "_" + suffix
                + " AFTER " + operation + " ON " + table + " BEGIN "
                + "UPDATE database_state SET revision=revision+1, "
                + "last_business_modified_at=strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id=1; END");
    }
}
