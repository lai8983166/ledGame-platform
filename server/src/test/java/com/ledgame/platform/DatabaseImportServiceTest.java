package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private DatabaseImportService service;
    private DatabaseBackupCoordinator coordinator;
    private RoomConnectionRegistry rooms;

    @BeforeEach
    void setup() throws Exception {
        mainDatabase = root.resolve("data/platform.db");
        candidateDatabase = root.resolve("candidate/platform.db");
        Files.createDirectories(mainDatabase.getParent());
        Files.createDirectories(candidateDatabase.getParent());
        DriverManagerDataSource mainDataSource = initialized(mainDatabase);
        DriverManagerDataSource candidateDataSource = initialized(candidateDatabase);
        mainJdbc = new JdbcTemplate(mainDataSource);
        JdbcTemplate candidateJdbc = new JdbcTemplate(candidateDataSource);
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
        service = new DatabaseImportService(coordinator, engine, new DatabaseFileInspector(), properties,
                new DatabaseStateService(mainJdbc), rooms, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-09-02T02:03:04Z"), ZoneOffset.UTC));
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
    void rejectsCandidateWithoutAUniqueEnabledFactoryAdministrator() {
        mainJdbc.execute("DELETE FROM operator_accounts");
        assertThatThrownBy(() -> service.registerExternal(mainDatabase))
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
        DatabaseBackupMetadata testMetadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "TEST", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), generatedAt, candidateDatabase.toString(), "test-disk",
                inspected.fileSize(), inspected.sha256(), inspected.integrityResult());
        mapper.writeValue(backupRoot.resolve("latest/metadata.json").toFile(), testMetadata);
        when(coordinator.backupRoot()).thenReturn(backupRoot);

        assertThat(service.discoverFixedCandidates()).isEmpty();

        DatabaseBackupMetadata productionMetadata = new DatabaseBackupMetadata(
                DatabaseBackupEngine.METADATA_FORMAT, "PRODUCTION", inspected.schemaVersion(),
                inspected.state().instanceId(), inspected.state().revision(),
                inspected.state().lastBusinessModifiedAt(), inspected.state().importedFromRevision(),
                inspected.state().importedAt(), generatedAt, candidateDatabase.toString(), "production-disk",
                inspected.fileSize(), inspected.sha256(), inspected.integrityResult());
        mapper.writeValue(backupRoot.resolve("latest/metadata.json").toFile(), productionMetadata);

        assertThat(service.discoverFixedCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.environment()).isEqualTo("PRODUCTION");
            assertThat(candidate.factoryAdminUsername()).isEqualTo("backup-admin");
            assertThat(candidate.memberCount()).isEqualTo(1);
        });
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
}
