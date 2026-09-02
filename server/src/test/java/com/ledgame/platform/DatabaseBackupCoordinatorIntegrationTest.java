package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseBackupCoordinatorIntegrationTest {
    @TempDir Path root;
    private Path source;
    private Path backupRoot;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private DatabaseFileInspector inspector;
    private DatabaseBackupCoordinator coordinator;

    @BeforeEach
    void setup() throws Exception {
        source = root.resolve("source/platform.db");
        backupRoot = root.resolve("other-disk/LEDGameBackup/member-admin");
        Files.createDirectories(source.getParent());
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + source.toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        new PlatformSchemaMigration(jdbc).run(new DefaultApplicationArguments(new String[0]));

        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setRootOverride(backupRoot.toString());
        properties.setMinimumFreeBytes(1);
        properties.setPollMillis(100);
        properties.setDebounceMillis(100);
        properties.setMaxDirtyMillis(500);
        properties.setRetryMillis(250);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        inspector = new DatabaseFileInspector();
        Clock clock = Clock.systemUTC();
        DatabaseBackupEngine engine = new DatabaseBackupEngine(new SqliteOnlineBackup(dataSource), inspector,
                mapper, properties, clock, "Asia/Shanghai", "jdbc:sqlite:" + source.toAbsolutePath());
        coordinator = new DatabaseBackupCoordinator(properties, mock(DiskTopologyProvider.class), engine,
                inspector, new DatabaseStateService(jdbc), new StartupGate(), mapper, clock, "Asia/Shanghai");
    }

    @AfterEach
    void close() {
        if (coordinator != null) coordinator.close();
    }

    @Test
    void createsInitialBackupAndCapturesCommittedWritesWithinBoundedDelay() throws Exception {
        coordinator.run(new DefaultApplicationArguments(new String[0]));
        assertThat(coordinator.status().state()).isEqualTo(BackupLifecycleState.READY_PROTECTED);
        long initialRevision = coordinator.status().backupRevision();

        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800138002', '自动备份会员', 'ACTIVE', 'now', 'now', 'test')
            """);

        await(Duration.ofSeconds(5), () -> coordinator.status().backupRevision() != null
                && coordinator.status().backupRevision() > initialRevision);
        JdbcTemplate backupJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + backupRoot.resolve("latest/platform.db").toAbsolutePath()));
        assertThat(backupJdbc.queryForObject(
                "SELECT COUNT(*) FROM members WHERE phone='13800138002'", Integer.class)).isEqualTo(1);
        assertThat(coordinator.status().protectedData()).isTrue();
    }

    @Test
    void fileFingerprintCatchesFutureWritesEvenWithoutRevisionTrigger() throws Exception {
        coordinator.run(new DefaultApplicationArguments(new String[0]));
        long revision = coordinator.status().backupRevision();
        jdbc.execute("CREATE TABLE future_business_data(id INTEGER PRIMARY KEY, value TEXT)");
        jdbc.update("INSERT INTO future_business_data(value) VALUES ('captured')");
        assertThat(new DatabaseStateService(jdbc).current().revision()).isEqualTo(revision);

        await(Duration.ofSeconds(5), () -> {
            try {
                JdbcTemplate backupJdbc = new JdbcTemplate(new DriverManagerDataSource(
                        "jdbc:sqlite:" + backupRoot.resolve("latest/platform.db").toAbsolutePath()));
                return backupJdbc.queryForObject("SELECT COUNT(*) FROM future_business_data", Integer.class) == 1;
            } catch (Exception ignored) { return false; }
        });
        assertThat(inspector.inspect(backupRoot.resolve("latest/platform.db")).valid()).isTrue();
    }

    @Test
    void shutdownFlushCapturesACommitWithoutWaitingForTheScheduler() {
        coordinator.run(new DefaultApplicationArguments(new String[0]));
        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800138003', '立即关闭会员', 'ACTIVE', 'now', 'now', 'test')
            """);

        assertThat(coordinator.flush()).isTrue();
        JdbcTemplate backupJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + backupRoot.resolve("latest/platform.db").toAbsolutePath()));
        assertThat(backupJdbc.queryForObject(
                "SELECT COUNT(*) FROM members WHERE phone='13800138003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void targetDropDegradesBusinessAndReconnectionOnChangedMountRecoversAutomatically() throws Exception {
        coordinator.close();
        Path sourceMount = source.getParent();
        Path initialTargetMount = Files.createDirectories(root.resolve("physical-target-e"));
        Path reconnectedMount = root.resolve("physical-target-f");
        AtomicReference<List<DiskVolume>> topology = new AtomicReference<>(List.of(
                volume(sourceMount, "source-disk", 0), volume(initialTargetMount, "backup-disk", 1)));
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setMinimumFreeBytes(1);
        properties.setPollMillis(100);
        properties.setDebounceMillis(100);
        properties.setMaxDirtyMillis(300);
        properties.setRetryMillis(250);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.systemUTC();
        DatabaseBackupEngine engine = new DatabaseBackupEngine(new SqliteOnlineBackup(dataSource), inspector,
                mapper, properties, clock, "Asia/Shanghai", "jdbc:sqlite:" + source.toAbsolutePath());
        coordinator = new DatabaseBackupCoordinator(properties, topology::get, engine, inspector,
                new DatabaseStateService(jdbc), new StartupGate(), mapper, clock, "Asia/Shanghai");
        coordinator.run(new DefaultApplicationArguments(new String[0]));
        assertThat(coordinator.status().protectedData()).isTrue();

        try (var paths = Files.walk(initialTargetMount)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        Files.writeString(initialTargetMount, "simulated disconnected volume");
        jdbc.update("""
            INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
            VALUES ('13800138004', '掉盘恢复会员', 'ACTIVE', 'now', 'now', 'test')
            """);
        await(Duration.ofSeconds(5), () -> coordinator.status().state() == BackupLifecycleState.READY_DEGRADED);

        Files.delete(initialTargetMount);
        Files.createDirectories(reconnectedMount);
        topology.set(List.of(volume(sourceMount, "source-disk", 0), volume(reconnectedMount, "backup-disk", 1)));
        await(Duration.ofSeconds(7), () -> coordinator.status().state() == BackupLifecycleState.READY_PROTECTED
                && Files.isRegularFile(reconnectedMount.resolve("LEDGameBackup/member-admin/latest/platform.db")));
        assertThat(coordinator.status().protectedData()).isTrue();
    }

    @Test
    void continuousWritesCannotPostponeBackupBeyondTheDirtyDeadline() throws Exception {
        coordinator.run(new DefaultApplicationArguments(new String[0]));
        long initialRevision = coordinator.status().backupRevision();
        Thread writer = new Thread(() -> {
            for (int index = 0; index < 20; index++) {
                jdbc.update("""
                    INSERT INTO members(phone, name, status, created_at, updated_at, created_by)
                    VALUES (?, '持续写入会员', 'ACTIVE', 'now', 'now', 'test')
                    """, "1770000" + String.format("%04d", index));
                try { Thread.sleep(40); } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        writer.start();
        await(Duration.ofSeconds(3), () -> writer.isAlive()
                && coordinator.status().backupRevision() != null
                && coordinator.status().backupRevision() > initialRevision);
        writer.join();
        await(Duration.ofSeconds(5), () -> coordinator.status().backupRevision() != null
                && coordinator.status().backupRevision() == new DatabaseStateService(jdbc).current().revision());
    }

    private static DiskVolume volume(Path mount, String id, int number) {
        return new DiskVolume(mount, id, "", number, "NVMe", "Fixed", "NTFS", false, 1_000_000_000L);
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition within " + timeout).isTrue();
    }
}
