package com.ledgame.platform;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DataProtectionMigration implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DataProtectionMigration.class);
    static final int FORMAT_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final DataProtectionKeyManager keys;
    private final ProtectedDataService protectedData;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final StartupGate startupGate;

    @Autowired
    public DataProtectionMigration(
            JdbcTemplate jdbc,
            DataProtectionKeyManager keys,
            ProtectedDataService protectedData,
            Clock clock,
            PlatformTransactionManager transactionManager,
            StartupGate startupGate) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.protectedData = protectedData;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.startupGate = startupGate;
    }

    DataProtectionMigration(
            JdbcTemplate jdbc,
            DataProtectionKeyManager keys,
            ProtectedDataService protectedData,
            Clock clock) {
        this(jdbc, keys, protectedData, clock, null, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean migrated = transactionManager == null
                    ? migrateInTransaction()
                    : Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                            .execute(status -> migrateInTransaction()));
            if (migrated) sanitizeLegacyPlaintextResidue();
            verifyProtectedSamples();
        } catch (RuntimeException exception) {
            if (startupGate == null) throw exception;
            BackupErrorCode code = errorFor(exception);
            LOG.error("data_protection_startup_blocked code={}", code, exception);
            startupGate.update(StartupGate.blocked(code));
        }
    }

    private static BackupErrorCode errorFor(Throwable failure) {
        String message = failureChain(failure);
        if (message.contains("DATA_PROTECTION_KEY_MISSING")) return BackupErrorCode.DATA_PROTECTION_KEY_MISSING;
        if (message.contains("DATA_PROTECTION_KEY_ID_MISMATCH")) return BackupErrorCode.DATA_PROTECTION_KEY_MISMATCH;
        if (message.contains("DATA_PROTECTION_KEY_READ_FAILED")
                || message.contains("DATA_PROTECTION_WINDOWS_REQUIRED")
                || message.contains("DATA_PROTECTION_KEY_CREATE_FAILED")) {
            return BackupErrorCode.DATA_PROTECTION_KEY_UNAVAILABLE;
        }
        if (message.contains("DATA_PROTECTION_INTEGRITY_FAILED")) return BackupErrorCode.DATA_PROTECTION_INTEGRITY_FAILED;
        return BackupErrorCode.DATA_PROTECTION_MIGRATION_FAILED;
    }

    private static String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            result.append(' ').append(current.getMessage());
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return result.toString();
    }

    private boolean migrateInTransaction() {
        ensureSchema();
        Map<String, Object> businessState = jdbc.queryForMap("""
                SELECT revision, last_business_modified_at,
                       imported_from_revision, imported_at
                  FROM database_state WHERE id=1
                """);
        List<Map<String, Object>> states = jdbc.queryForList(
                "SELECT format_version, key_id, status FROM data_protection_state WHERE id=1");
        DataKeyMaterial key = states.isEmpty() ? keys.loadOrCreate() : keys.loadExisting();
        if (!states.isEmpty()) {
            Map<String, Object> state = states.get(0);
            String recordedKeyId = String.valueOf(state.get("key_id"));
            if (!key.keyId().equals(recordedKeyId)) {
                throw new IllegalStateException("DATA_PROTECTION_KEY_ID_MISMATCH: database="
                        + recordedKeyId + ", key=" + key.keyId());
            }
            if ("COMPLETE".equals(String.valueOf(state.get("status")))) {
                verifyProtectedSamples();
                return false;
            }
        } else {
            jdbc.update("""
                INSERT INTO data_protection_state(id, format_version, key_id, status, completed_at)
                VALUES (1, ?, ?, 'MIGRATING', NULL)
                """, FORMAT_VERSION, key.keyId());
        }

        protectColumn("members", "phone");
        protectColumn("members", "name");
        protectColumn("members", "avatar_id");
        protectColumn("members", "birthday");
        protectColumn("members", "gender");
        populateLookup("members", "phone", "phone_lookup_hash", "members.phone");

        protectColumn("wristbands", "card_uid");
        populateLookup("wristbands", "card_uid", "card_uid_lookup_hash", "wristbands.card_uid");
        protectColumn("wristband_charge_records", "wristband_uid");
        protectColumn("game_play_records", "wristband_uid");
        protectColumn("game_play_records", "result_json");
        protectColumn("operator_action_logs", "operator_username");
        protectColumn("operator_action_logs", "operator_display_name");
        protectColumn("operator_action_logs", "target_id");
        protectColumn("operator_action_logs", "summary_json");

        jdbc.execute("DROP INDEX IF EXISTS ux_members_active_phone");
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS ux_members_active_phone_lookup
                ON members(phone_lookup_hash) WHERE status='ACTIVE' AND deleted_at IS NULL
            """);
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS ux_wristbands_card_uid_lookup
                ON wristbands(card_uid_lookup_hash)
            """);
        jdbc.update("""
                UPDATE database_state
                   SET revision=?, last_business_modified_at=?,
                       imported_from_revision=?, imported_at=?
                 WHERE id=1
                """, businessState.get("revision"), businessState.get("last_business_modified_at"),
                businessState.get("imported_from_revision"), businessState.get("imported_at"));
        verifyProtectedSamples();
        jdbc.update("""
            UPDATE data_protection_state
               SET format_version=?, key_id=?, status='COMPLETE', completed_at=?
             WHERE id=1
            """, FORMAT_VERSION, key.keyId(), clock.instant().toString());
        return true;
    }

    private void sanitizeLegacyPlaintextResidue() {
        try {
            jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            jdbc.execute("VACUUM");
            jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("DATA_PROTECTION_STORAGE_SANITIZE_FAILED", exception);
        }
    }

    private void ensureSchema() {
        addColumnIfMissing("members", "phone_lookup_hash", "TEXT");
        addColumnIfMissing("wristbands", "card_uid_lookup_hash", "TEXT");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS data_protection_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                format_version INTEGER NOT NULL,
                key_id TEXT NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('MIGRATING', 'COMPLETE')),
                completed_at TEXT)
            """);
    }

    private void addColumnIfMissing(String table, String column, String type) {
        boolean present = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
        if (!present) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    private void protectColumn(String table, String column) {
        if (!tableExists(table)) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + column + " AS protected_value FROM " + table
                        + " WHERE " + column + " IS NOT NULL");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String value = String.valueOf(row.get("protected_value"));
            String context = context(table, column, id);
            if (protectedData.isEncrypted(value)) {
                protectedData.decrypt(context, value);
            } else {
                jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=?",
                        protectedData.encrypt(context, value), id);
            }
        }
    }

    private void populateLookup(String table, String encryptedColumn, String hashColumn, String hashContext) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + encryptedColumn + " AS protected_value, " + hashColumn
                        + " AS lookup_value FROM " + table);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String protectedValue = (String) row.get("protected_value");
            if (protectedValue == null) continue;
            String plaintext = protectedData.decrypt(context(table, encryptedColumn, id), protectedValue);
            String expected = protectedData.lookupHash(hashContext, plaintext);
            if (!expected.equals(row.get("lookup_value"))) {
                jdbc.update("UPDATE " + table + " SET " + hashColumn + "=? WHERE id=?", expected, id);
            }
        }
    }

    private void verifyProtectedSamples() {
        verifyColumn("members", "phone");
        verifyColumn("members", "name");
        verifyColumn("members", "avatar_id");
        verifyColumn("members", "birthday");
        verifyColumn("members", "gender");
        verifyColumn("wristbands", "card_uid");
        verifyColumn("wristband_charge_records", "wristband_uid");
        verifyColumn("game_play_records", "wristband_uid");
        verifyColumn("game_play_records", "result_json");
        verifyColumn("operator_action_logs", "operator_username");
        verifyColumn("operator_action_logs", "operator_display_name");
        verifyColumn("operator_action_logs", "target_id");
        verifyColumn("operator_action_logs", "summary_json");
        verifyLookup("members", "phone", "phone_lookup_hash", "members.phone");
        verifyLookup("wristbands", "card_uid", "card_uid_lookup_hash", "wristbands.card_uid");
    }

    private void verifyLookup(String table, String encryptedColumn, String hashColumn, String hashContext) {
        if (!tableExists(table)) return;
        Integer missing = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE "
                + encryptedColumn + " IS NOT NULL AND (" + hashColumn + " IS NULL OR " + hashColumn + "='')",
                Integer.class);
        if (missing != null && missing > 0) {
            throw new IllegalStateException("DATA_PROTECTION_LOOKUP_INCOMPLETE: " + table + "." + hashColumn);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, " + encryptedColumn
                + " AS protected_value, " + hashColumn + " AS lookup_value FROM " + table + " LIMIT 20");
        for (Map<String, Object> row : rows) {
            if (row.get("protected_value") == null) continue;
            String plaintext = protectedData.decryptField(table, encryptedColumn, row.get("protected_value"));
            String expected = protectedData.lookupHash(hashContext, plaintext);
            if (!expected.equals(row.get("lookup_value"))) {
                throw new IllegalStateException("DATA_PROTECTION_LOOKUP_MISMATCH: " + table + "." + hashColumn);
            }
        }
    }

    private void verifyColumn(String table, String column) {
        if (!tableExists(table)) return;
        Integer plaintext = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column
                        + " IS NOT NULL AND " + column + " NOT LIKE 'enc:v1:%'",
                Integer.class);
        if (plaintext != null && plaintext > 0) {
            throw new IllegalStateException("DATA_PROTECTION_MIGRATION_INCOMPLETE: " + table + "." + column);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + column + " AS protected_value FROM " + table
                        + " WHERE " + column + " IS NOT NULL LIMIT 20");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String value = String.valueOf(row.get("protected_value"));
            if (!protectedData.isEncrypted(value)) {
                throw new IllegalStateException("DATA_PROTECTION_MIGRATION_INCOMPLETE: " + table + "." + column);
            }
            protectedData.decrypt(context(table, column, id), value);
        }
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }

    static String context(String table, String column, long id) {
        // Column-bound AAD lets callers encrypt before INSERT, so plaintext never reaches SQLite/WAL.
        return table + "." + column;
    }
}
