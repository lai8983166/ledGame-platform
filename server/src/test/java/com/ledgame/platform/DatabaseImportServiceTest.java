package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseImportServiceTest {
    @TempDir Path root;
    private Path mainDatabase;
    private Path candidateDatabase;
    private JdbcTemplate mainJdbc;
    private JdbcTemplate candidateJdbc;
    private DatabaseImportService service;
    private DatabaseBackupCoordinator coordinator;
    private RoomConnectionRegistry rooms;
    private ProtectedDataService protectedData;
    private DataProtectionKeyManager keyManager;

    @BeforeEach
    void setup() throws Exception {
        mainDatabase = root.resolve("data/platform.db");
        candidateDatabase = root.resolve("candidate/platform.db");
        Files.createDirectories(mainDatabase.getParent());
        Files.createDirectories(candidateDatabase.getParent());
        DriverManagerDataSource mainDataSource = initialized(mainDatabase);
        DriverManagerDataSource candidateDataSource = initialized(candidateDatabase);
        mainJdbc = new JdbcTemplate(mainDataSource);
        candidateJdbc = new JdbcTemplate(candidateDataSource);
        candidateJdbc.update("""
            INSERT INTO operator_accounts(username, display_name, password_hash, account_type, enabled,
                                          created_by_operator_id, created_at, updated_at)
            VALUES ('backup-admin', '备份出厂管理员', 'hash', 'FACTORY_ADMIN', 1, NULL, 'now', 'now')
            """);
        candidateJdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800138001', '导入候选会员', 'ACTIVE', 'now', 'now', 'test')
            """);

        coordinator = mock(DatabaseBackupCoordinator.class);
        when(coordinator.status()).thenReturn(StartupGate.degraded(
                BackupErrorCode.NO_CROSS_DISK_TARGET, null, null, 0, null));
        DatabaseBackupEngine engine = mock(DatabaseBackupEngine.class);
        when(engine.sourceDatabase()).thenReturn(mainDatabase);
        rooms = mock(RoomConnectionRegistry.class);
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setEnvironment("PRODUCTION");
        DataKeyMaterial key = DataProtectionKeyManager.material(new byte[32]);
        keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(key);
        when(keyManager.loadExisting()).thenReturn(key);
        protectedData = new ProtectedDataService(keyManager);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-02T02:03:04Z"), ZoneOffset.UTC);
        new DataProtectionMigration(mainJdbc, keyManager, protectedData, fixedClock)
                .run(new DefaultApplicationArguments());
        new DataProtectionMigration(candidateJdbc, keyManager, protectedData, fixedClock)
                .run(new DefaultApplicationArguments());
        service = new DatabaseImportService(coordinator, engine, new DatabaseFileInspector(), properties,
                new DatabaseStateService(mainJdbc), rooms, new ObjectMapper().findAndRegisterModules(),
                fixedClock, protectedData, keyManager);
    }

    @Test
    void validatesExternalCandidateAndPreparesAuditedHigherRevisionCopy() {
        DatabaseBackupCandidate candidate = service.registerExternal(candidateDatabase);
        assertThat(candidate.factoryAdminUsername()).isEqualTo("backup-admin");
        assertThat(candidate.memberCount()).isEqualTo(1);
        DatabaseImportManifest manifest = service.prepare(candidate.candidateId());

        InspectedDatabase prepared = new DatabaseFileInspector().inspect(Path.of(manifest.preparedDatabasePath()));
        assertThat(prepared.valid()).isTrue();
        assertThat(prepared.sha256()).isEqualTo(manifest.sha256());
        assertThat(prepared.state().revision()).isGreaterThan(new DatabaseStateService(mainJdbc).current().revision());
        assertThat(prepared.state().importedFromRevision()).isEqualTo(candidate.revision());
        assertThat(prepared.state().importedAt()).isEqualTo(Instant.parse("2026-09-02T02:03:04Z"));
        verify(coordinator).beginImport();
    }

    @Test
    void rejectsImportWhileAnyRoomIsRunningOrQueued() {
        DatabaseBackupCandidate candidate = service.registerExternal(candidateDatabase);
        when(rooms.hasActiveBusiness()).thenReturn(true);

        assertThatThrownBy(() -> service.prepare(candidate.candidateId()))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_BUSINESS_ACTIVE"));
    }

    @Test
    void rejectsCorruptCandidateBeforeItCanReachConfirmation() throws Exception {
        Path corrupt = root.resolve("corrupt.db");
        Files.writeString(corrupt, "not sqlite");
        assertThatThrownBy(() -> service.registerExternal(corrupt))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_CANDIDATE_INVALID"));
    }

    @Test
    void rejectsDatabaseFromANewerUnsupportedSchemaVersion() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + candidateDatabase.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=999");
        }
        assertThatThrownBy(() -> service.registerExternal(candidateDatabase))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_CANDIDATE_INVALID"));
    }

    @Test
    void rejectsWrongDataKeyIdentityBeforeChangingTheCurrentDatabase() {
        DatabaseBackupCandidate candidate = service.registerExternal(candidateDatabase);
        long mainRevision = new DatabaseStateService(mainJdbc).current().revision();
        candidateJdbc.update("UPDATE data_protection_state SET key_id='wrong-key-id' WHERE id=1");

        assertThatThrownBy(() -> service.prepare(candidate.candidateId()))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_CANDIDATE_INVALID"));
        assertThat(new DatabaseStateService(mainJdbc).current().revision()).isEqualTo(mainRevision);
        verify(coordinator, never()).beginImport();
    }

    @Test
    void rejectsTamperedCiphertextBeforeChangingTheCurrentDatabase() {
        DatabaseBackupCandidate candidate = service.registerExternal(candidateDatabase);
        String encrypted = candidateJdbc.queryForObject("SELECT phone FROM members LIMIT 1", String.class);
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");
        candidateJdbc.update("UPDATE members SET phone=?", tampered);
        long mainRevision = new DatabaseStateService(mainJdbc).current().revision();

        assertThatThrownBy(() -> service.prepare(candidate.candidateId()))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_CANDIDATE_INVALID"));
        assertThat(new DatabaseStateService(mainJdbc).current().revision()).isEqualTo(mainRevision);
        verify(coordinator, never()).beginImport();
    }

    @Test
    void rejectsCandidateWithoutAUniqueEnabledFactoryAdministrator() {
        mainJdbc.execute("DELETE FROM operator_accounts");
        assertThatThrownBy(() -> service.registerExternal(mainDatabase))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_FACTORY_ACCOUNT_INVALID"));
    }

    @Test
    void rejectsCandidateWhoseOnlyFactoryAdministratorIsDisabled() {
        candidateJdbc.update("UPDATE operator_accounts SET enabled=0 WHERE account_type='FACTORY_ADMIN'");

        assertThatThrownBy(() -> service.registerExternal(candidateDatabase))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_FACTORY_ACCOUNT_INVALID"));
    }

    @Test
    void rejectsCandidateWithMultipleEnabledFactoryAdministrators() {
        candidateJdbc.update("""
            INSERT INTO operator_accounts(username, display_name, password_hash, account_type, enabled,
                                          created_by_operator_id, created_at, updated_at)
            VALUES ('backup-admin-2', '第二出厂管理员', 'hash', 'FACTORY_ADMIN', 1, NULL, 'now', 'now')
            """);

        assertThatThrownBy(() -> service.registerExternal(candidateDatabase))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IMPORT_FACTORY_ACCOUNT_INVALID"));
    }

    @Test
    void fixedCandidateDiscoveryOnlyReturnsCurrentEnvironmentV2Backups() throws Exception {
        Path backupRoot = root.resolve("fixed-backup");
        Path latest = backupRoot.resolve("latest/platform.db");
        Files.createDirectories(latest.getParent());
        Files.copy(candidateDatabase, latest, StandardCopyOption.REPLACE_EXISTING);
        DatabaseFileInspector inspector = new DatabaseFileInspector();
        InspectedDatabase inspected = inspector.inspect(latest);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant generatedAt = Instant.parse("2026-09-02T02:03:04Z");
        Path recoveryEnvelope = backupRoot.resolve("latest/factory-key-envelope.json");
        Files.writeString(recoveryEnvelope, mapper.writeValueAsString(new DatabaseRecoveryEnvelope(
                DatabaseRecoveryEnvelope.FORMAT, DatabaseRecoveryEnvelope.ALGORITHM,
                "factory-recovery-v1", protectedData.keyId(), "A".repeat(256))));
        DatabaseBackupMetadata testMetadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "TEST", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), generatedAt, candidateDatabase.toString(), "test-disk",
                inspected.fileSize(), inspected.sha256(), inspected.integrityResult(),
                ProtectedDataService.ENCRYPTION_VERSION, protectedData.keyId(), "factory-recovery-v1",
                DatabaseRecoveryEnvelope.FORMAT, inspector.sha256(recoveryEnvelope));
        mapper.writeValue(backupRoot.resolve("latest/metadata.json").toFile(), testMetadata);
        when(coordinator.backupRoot()).thenReturn(backupRoot);

        assertThat(service.discoverFixedCandidates()).isEmpty();

        DatabaseBackupMetadata productionMetadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), generatedAt, candidateDatabase.toString(), "production-disk",
                inspected.fileSize(), inspected.sha256(), inspected.integrityResult(),
                ProtectedDataService.ENCRYPTION_VERSION, protectedData.keyId(), "factory-recovery-v1",
                DatabaseRecoveryEnvelope.FORMAT, inspector.sha256(recoveryEnvelope));
        mapper.writeValue(backupRoot.resolve("latest/metadata.json").toFile(), productionMetadata);

        assertThat(service.discoverFixedCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.environment()).isEqualTo("PRODUCTION");
            assertThat(candidate.factoryAdminUsername()).isEqualTo("backup-admin");
            assertThat(candidate.memberCount()).isEqualTo(1);
        });
    }

    @Test
    void fixedCandidateWithAnUnusableWindowsKeyEnvelopeIsHidden() throws Exception {
        Path backupRoot = root.resolve("wrong-envelope-backup");
        Path latest = backupRoot.resolve("latest/platform.db");
        Files.createDirectories(latest.getParent());
        Files.copy(candidateDatabase, latest, StandardCopyOption.REPLACE_EXISTING);
        Path envelope = Files.writeString(backupRoot.resolve("latest/data-key.dpapi"), "wrong-envelope");
        DatabaseFileInspector inspector = new DatabaseFileInspector();
        InspectedDatabase inspected = inspector.inspect(latest);
        DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), Instant.parse("2026-09-02T02:03:04Z"),
                candidateDatabase.toString(), "production-disk", inspected.fileSize(), inspected.sha256(),
                inspected.integrityResult(), ProtectedDataService.ENCRYPTION_VERSION, protectedData.keyId());
        new ObjectMapper().findAndRegisterModules()
                .writeValue(backupRoot.resolve("latest/metadata.json").toFile(), metadata);
        when(coordinator.backupRoot()).thenReturn(backupRoot);
        when(keyManager.requiresProtectedEnvelope()).thenReturn(true);
        when(keyManager.loadEnvelope(envelope)).thenThrow(new IllegalStateException("DPAPI failed"));

        assertThat(service.discoverFixedCandidates()).isEmpty();
    }

    @Test
    void sameWindowsUserCanPrepareABackupEncryptedWithADifferentLocalInstallKey() throws Exception {
        Path alternateMain = root.resolve("reinstall/main.db");
        Path alternateCandidate = root.resolve("previous-install/platform.db");
        Files.createDirectories(alternateMain.getParent());
        Files.createDirectories(alternateCandidate.getParent());
        DriverManagerDataSource newInstallSource = initialized(alternateMain);
        DriverManagerDataSource oldInstallSource = initialized(alternateCandidate);
        JdbcTemplate newInstallJdbc = new JdbcTemplate(newInstallSource);
        JdbcTemplate oldInstallJdbc = new JdbcTemplate(oldInstallSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WindowsDataProtector sameUserProtector = new WindowsDataProtector() {
            @Override public byte[] protect(byte[] plaintext) { return plaintext.clone(); }
            @Override public byte[] unprotect(byte[] ciphertext) { return ciphertext.clone(); }
        };
        DataProtectionProperties newProperties = new DataProtectionProperties();
        newProperties.setKeyPath(root.resolve("reinstall/security/data-key.dpapi").toString());
        DataProtectionProperties oldProperties = new DataProtectionProperties();
        oldProperties.setKeyPath(root.resolve("previous-install/data-key.dpapi").toString());
        DataProtectionKeyManager newKeys = new DataProtectionKeyManager(
                newProperties, sameUserProtector, mapper, "jdbc:sqlite:" + alternateMain);
        DataProtectionKeyManager oldKeys = new DataProtectionKeyManager(
                oldProperties, sameUserProtector, mapper, "jdbc:sqlite:" + alternateCandidate);
        ProtectedDataService newData = new ProtectedDataService(newKeys);
        ProtectedDataService oldData = new ProtectedDataService(oldKeys);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-02T02:03:04Z"), ZoneOffset.UTC);
        new DataProtectionMigration(newInstallJdbc, newKeys, newData, fixedClock)
                .run(new DefaultApplicationArguments());
        new DataProtectionMigration(oldInstallJdbc, oldKeys, oldData, fixedClock)
                .run(new DefaultApplicationArguments());
        assertThat(newData.keyId()).isNotEqualTo(oldData.keyId());
        oldInstallJdbc.update("""
            INSERT INTO operator_accounts(username, display_name, password_hash, account_type, enabled,
                                          created_by_operator_id, created_at, updated_at)
            VALUES ('old-admin', '旧安装管理员', 'hash', 'FACTORY_ADMIN', 1, NULL, 'now', 'now')
            """);
        oldInstallJdbc.update("""
            INSERT INTO members(phone, phone_lookup_hash, name, status, created_at, updated_at, created_by)
            VALUES (?, ?, ?, 'ACTIVE', 'now', 'now', 'test')
            """, oldData.encryptField("members", "phone", "13800990000"),
                oldData.phoneLookupHash("13800990000"), oldData.encryptField("members", "name", "旧库会员"));

        Path backupRoot = root.resolve("previous-backup");
        Path latest = backupRoot.resolve("latest/platform.db");
        Files.createDirectories(latest.getParent());
        Files.copy(alternateCandidate, latest);
        Files.copy(oldKeys.keyPath(), backupRoot.resolve("latest/data-key.dpapi"));
        DatabaseFileInspector inspector = new DatabaseFileInspector();
        InspectedDatabase inspected = inspector.inspect(latest);
        DatabaseBackupMetadata metadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), fixedClock.instant(), alternateCandidate.toString(), "old-disk",
                inspected.fileSize(), inspected.sha256(), inspected.integrityResult(),
                ProtectedDataService.ENCRYPTION_VERSION, oldData.keyId());
        mapper.writeValue(backupRoot.resolve("latest/metadata.json").toFile(), metadata);

        DatabaseBackupCoordinator localCoordinator = mock(DatabaseBackupCoordinator.class);
        when(localCoordinator.backupRoot()).thenReturn(backupRoot);
        when(localCoordinator.status()).thenReturn(StartupGate.degraded(
                BackupErrorCode.NO_CROSS_DISK_TARGET, null, null, 0, null));
        DatabaseBackupEngine localEngine = mock(DatabaseBackupEngine.class);
        when(localEngine.sourceDatabase()).thenReturn(alternateMain);
        DatabaseImportService localService = new DatabaseImportService(
                localCoordinator, localEngine, inspector, properties("PRODUCTION"),
                new DatabaseStateService(newInstallJdbc), mock(RoomConnectionRegistry.class), mapper,
                fixedClock, newData, newKeys);

        DatabaseBackupCandidate discovered = localService.discoverFixedCandidates().get(0);
        DatabaseImportManifest manifest = localService.prepare(discovered.candidateId());

        assertThat(Path.of(manifest.preparedKeyEnvelopePath())).isRegularFile();
        assertThat(manifest.keyEnvelopeSha256()).isEqualTo(inspector.sha256(
                Path.of(manifest.preparedKeyEnvelopePath())));
        verify(localCoordinator).beginImport();
    }

    private DriverManagerDataSource initialized(Path path) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + path.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));
        return dataSource;
    }

    private static DatabaseBackupProperties properties(String environment) {
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setEnvironment(environment);
        return properties;
    }
}
