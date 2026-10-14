package com.ledgame.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseArchiveServiceTest {
    @TempDir Path root;
    private Path database;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseBackupEngine engine;
    private DatabaseArchiveService archives;
    private DatabaseImportService imports;
    private AvatarStorageService avatars;
    private DataProtectionKeyManager keys;
    private DatabaseBackupCoordinator coordinator;
    private final byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @BeforeEach void setup() throws Exception {
        database = root.resolve("data/platform.db");
        Files.createDirectories(database.getParent());
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql")); }
        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments());
        keys = mock(DataProtectionKeyManager.class);
        var storage = new ProtectedDataService(keys);
        new DataProtectionMigration(jdbc, keys, storage, Clock.systemUTC()).run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO operator_accounts(username,display_name,password_hash,account_type,enabled,created_at,updated_at) VALUES ('admin','厂家','unchanged-password-hash','FACTORY_ADMIN',1,'now','now')");
        avatars = new AvatarStorageService(storage, "jdbc:sqlite:" + database);
        var avatar = avatars.store(Base64.getEncoder().encodeToString(image), "image/png");
        jdbc.update("INSERT INTO members(phone,name,avatar_id,status,created_at,updated_at,created_by) VALUES ('13800138000','导出会员',?,'ACTIVE','now','now','test')",avatar);
        var properties = new DatabaseBackupProperties();
        properties.setEnvironment("TEST");
        var mapper = new ObjectMapper().findAndRegisterModules();
        engine = new DatabaseBackupEngine(new SqliteOnlineBackup(dataSource),new DatabaseFileInspector(),mapper,properties,Clock.systemUTC(),"Asia/Shanghai","jdbc:sqlite:"+database,keys,storage,avatars);
        archives = new DatabaseArchiveService(engine);
        coordinator = mock(DatabaseBackupCoordinator.class);
        when(coordinator.status()).thenReturn(StartupGate.degraded(BackupErrorCode.NO_CROSS_DISK_TARGET,null,null,0,null));
        imports = new DatabaseImportService(coordinator,engine,new DatabaseFileInspector(),properties,new DatabaseStateService(jdbc),mock(RoomConnectionRegistry.class),mapper,Clock.systemUTC(),storage,keys);
    }

    @Test void plaintextArchiveRoundTripIncludesDatabasePhotosAndPasswordsWithoutKeys() throws Exception {
        Path zip = root.resolve("全量备份.zip");
        archives.export(zip);
        assertThat(zip).isRegularFile();
        Path extracted = DatabaseArchiveService.extract(zip,root.resolve("inspect"));
        assertThat(extracted.resolveSibling("data-key.dpapi")).doesNotExist();
        assertThat(extracted.resolveSibling("factory-key-envelope.json")).doesNotExist();
        assertThat(Files.readAllBytes(extracted.getParent().resolve("avatars").resolve(avatars.storedFiles().get(0).getFileName()))).containsExactly(image);
        var candidate = imports.registerExternal(zip);
        var prepared = imports.prepare(candidate.candidateId());
        var restored = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+prepared.preparedDatabasePath()));
        assertThat(restored.queryForObject("SELECT name FROM members",String.class)).isEqualTo("导出会员");
        assertThat(restored.queryForObject("SELECT password_hash FROM operator_accounts",String.class)).isEqualTo("unchanged-password-hash");
        assertThat(Path.of(prepared.preparedAvatarDirectoryPath()).resolve(avatars.storedFiles().get(0).getFileName())).isRegularFile();
        assertThat(prepared.preparedKeyEnvelopePath()).isNull();
        verify(coordinator).beginImport();
        verifyNoInteractions(keys);
    }

    @Test void exportCapturesCommittedDataNotUncommittedTransaction() throws Exception {
        jdbc.execute("PRAGMA journal_mode=WAL");
        try (var transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try (var statement = transaction.createStatement()) { statement.execute("UPDATE members SET name='未提交'"); }
            Path zip = root.resolve("事务备份.zip");
            archives.export(zip);
            Path extracted = DatabaseArchiveService.extract(zip,root.resolve("snapshot"));
            assertThat(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+extracted)).queryForObject("SELECT name FROM members",String.class)).isEqualTo("导出会员");
            transaction.rollback();
        }
    }

    @Test void missingReferencedPhotoFailsWithoutReplacingExistingExport() throws Exception {
        Path zip = Files.writeString(root.resolve("existing.zip"),"previous export");
        Files.delete(avatars.storedFiles().get(0));
        assertThatThrownBy(() -> archives.export(zip)).isInstanceOf(PlatformApiException.class);
        assertThat(Files.readString(zip)).isEqualTo("previous export");
    }

    @Test void exportCannotOverwriteSourceOrUseDatabaseExtension() {
        assertThatThrownBy(() -> archives.export(database)).isInstanceOf(PlatformApiException.class);
        assertThatThrownBy(() -> archives.export(database.resolveSibling("unsafe.zip"))).isInstanceOf(PlatformApiException.class);
        assertThat(new DatabaseFileInspector().inspect(database).valid()).isTrue();
    }

    @Test void rejectsTraversalUnexpectedEntriesDuplicatesAndIncompleteArchives() throws Exception {
        for (String[] names : new String[][] {{"../platform.db"},{"C:/platform.db"},{"avatars/../../outside.bin"},{"extra.exe"},{"platform.db","platform.db"},{"metadata.json"}}) {
            Path zip = root.resolve(java.util.UUID.randomUUID()+".zip");
            // ZipOutputStream itself refuses exact duplicates; case variants are
            // also rejected by the archive whitelist on Windows.
            if (names.length == 2) names[1] = "Platform.db";
            writeZip(zip,names);
            assertThatThrownBy(() -> imports.registerExternal(zip)).isInstanceOf(PlatformApiException.class);
        }
        verify(coordinator,never()).beginImport();
    }

    @Test void checksumTamperingFailsBeforeImport() throws Exception {
        Path zip = root.resolve("valid.zip"); archives.export(zip);
        Path extracted = DatabaseArchiveService.extract(zip,root.resolve("tamper"));
        new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+extracted)).update("UPDATE members SET name='已修改'");
        assertThatThrownBy(() -> imports.registerExternal(extracted)).isInstanceOf(PlatformApiException.class);
        verify(coordinator,never()).beginImport();
    }

    @Test void brandingFileIsIncludedAndVerifiedInImportManifest() throws Exception {
        var branding = new BrandingStorageService("jdbc:sqlite:"+database);
        var icon = branding.store(Base64.getEncoder().encodeToString(image),"image/png");
        jdbc.update("INSERT INTO store_settings(id,app_title,app_icon_path,app_icon_sha256,unit_price_cents,secondary_display_enabled,created_at,updated_at) VALUES (1,'品牌',?,?,100,0,'now','now') ON CONFLICT(id) DO UPDATE SET app_icon_path=excluded.app_icon_path,app_icon_sha256=excluded.app_icon_sha256",icon.path(),icon.sha256());
        Path zip = root.resolve("brand.zip"); archives.export(zip);
        var candidate = imports.registerExternal(zip);
        var manifest = imports.prepare(candidate.candidateId());
        assertThat(manifest.brandingFileName()).isEqualTo("app-icon.png");
        assertThat(manifest.brandingFileSha256()).isEqualTo(icon.sha256());
        assertThat(Files.readAllBytes(Path.of(manifest.preparedBrandingDirectoryPath()).resolve(manifest.brandingFileName()))).containsExactly(image);
    }

    @Test void cancelledSelectionCleansOnlyOwnedArchiveNotCustomerZip() throws Exception {
        Path zip = root.resolve("cancel.zip"); archives.export(zip);
        var candidate = imports.registerExternal(zip);
        imports.releaseCandidate(candidate.candidateId());
        assertThat(zip).isRegularFile();
        try (var children = Files.list(database.getParent().resolve("import-staging"))) {
            assertThat(children.filter(path -> path.getFileName().toString().startsWith("archive-"))).isEmpty();
        }
        verify(coordinator,never()).beginImport();
    }

    @Test void missingAvatarOrTamperedAvatarManifestFailsBeforeConfirmation() throws Exception {
        Path zip = root.resolve("valid.zip"); archives.export(zip);
        Path extracted = DatabaseArchiveService.extract(zip,root.resolve("photo"));
        Files.write(extracted.getParent().resolve("avatars").resolve(avatars.storedFiles().get(0).getFileName()),new byte[] {1,2,3});
        assertThatThrownBy(() -> imports.registerExternal(extracted)).isInstanceOf(PlatformApiException.class);
        verify(coordinator,never()).beginImport();
    }

    private void writeZip(Path target,String[] names) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[] {1,2,3}); zip.closeEntry(); }
        }
    }
}
