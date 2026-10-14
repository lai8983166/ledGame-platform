package com.ledgame.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** One portable file, built from the same verified online snapshot as automatic backups. */
@Service
public class DatabaseArchiveService {
    private static final Set<String> FILES = Set.of("platform.db", "metadata.json", "avatar-manifest.json",
            "data-key.dpapi", "factory-key-envelope.json");
    private final DatabaseBackupEngine engine;

    public DatabaseArchiveService(DatabaseBackupEngine engine) { this.engine = engine; }

    public String export(Path selectedPath) {
        Path destination = selectedPath.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                || destination.startsWith(engine.sourceDatabase().getParent())) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "DATABASE_EXPORT_PATH_INVALID", "请选择数据目录以外的 ZIP 保存位置");
        }
        Path staging = null;
        Path writing = destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".writing");
        try {
            staging = Files.createTempDirectory("ledgame-database-export-");
            engine.backup(staging, "MANUAL");
            Path latest = staging.resolve("latest");
            Path icon = verifiedBrandingFile(latest.resolve("platform.db"), engine.sourceDatabase().getParent());
            Files.createDirectories(latest.resolve("branding"));
            if (icon != null) Files.copy(icon,latest.resolve("branding").resolve(icon.getFileName()));
            verifiedBrandingFile(latest.resolve("platform.db"),latest);
            try (var archive = new ZipOutputStream(Files.newOutputStream(writing)); var paths = Files.walk(latest)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = latest.relativize(file).toString().replace('\\', '/');
                    archive.putNextEntry(new ZipEntry(name));
                    Files.copy(file, archive);
                    archive.closeEntry();
                }
            }
            move(writing, destination);
            return destination.toString();
        } catch (Exception exception) {
            if (exception instanceof PlatformApiException api) throw api;
            throw new PlatformApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_EXPORT_FAILED", "数据库导出失败，请检查保存位置权限、磁盘空间及头像文件是否完整");
        } finally {
            try { Files.deleteIfExists(writing); } catch (IOException ignored) { }
            deleteTree(staging);
        }
    }

    static Path extract(Path archive, Path stagingParent) {
        Path root = null;
        try {
            Files.createDirectories(stagingParent);
            root = Files.createTempDirectory(stagingParent, "archive-").toAbsolutePath().normalize();
            Set<String> seen = new HashSet<>();
            long total = 0;
            try (var input = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName();
                    boolean avatar = name.matches("avatars/[0-9a-fA-F-]{36}\\.bin");
                    boolean branding = name.matches("branding/app-icon\\.(png|jpg|webp)");
                    if (entry.isDirectory()) {
                        if ((!name.equals("avatars/") && !name.equals("branding/")) || !seen.add(name)) throw new IOException("invalid directory");
                        Files.createDirectories(root.resolve(name));
                        continue;
                    }
                    if ((!FILES.contains(name) && !avatar && !branding) || !seen.add(name) || seen.size() > 1_000_000) {
                        throw new IOException("invalid archive entry");
                    }
                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root)) throw new IOException("invalid archive path");
                    Files.createDirectories(target.getParent());
                    long entryBytes = 0;
                    try (var output = Files.newOutputStream(target)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            entryBytes += count;
                            total += count;
                            long limit = avatar || branding ? AvatarStorageService.MAX_BYTES + 1024L
                                    : name.equals("platform.db") ? 16L * 1024 * 1024 * 1024 : 64L * 1024 * 1024;
                            if (entryBytes > limit || total > 32L * 1024 * 1024 * 1024) throw new IOException("archive too large");
                            output.write(buffer, 0, count);
                        }
                    }
                    input.closeEntry();
                }
            }
            if (!seen.containsAll(Set.of("platform.db", "metadata.json", "avatar-manifest.json"))) {
                throw new IOException("incomplete archive");
            }
            Files.createDirectories(root.resolve("avatars"));
            Files.createDirectories(root.resolve("branding"));
            return root.resolve("platform.db");
        } catch (Exception exception) {
            deleteTree(root);
            throw new PlatformApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_ARCHIVE_INVALID", "备份文件无效、不完整或包含不安全路径");
        }
    }

    static Path verifiedBrandingFile(Path database, Path dataDirectory) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT app_icon_path,app_icon_sha256 FROM store_settings WHERE id=1")) {
            if (!rows.next()) return null;
            String path = rows.getString(1);
            if (path == null || path.isBlank()) return null;
            if (!path.matches("branding/app-icon\\.(png|jpg|webp)")) throw new IOException("invalid icon path");
            Path icon = dataDirectory.resolve(path);
            if (!Files.isRegularFile(icon) || Files.size(icon) > BrandingStorageService.MAX_BYTES
                    || !new DatabaseFileInspector().sha256(icon).equalsIgnoreCase(rows.getString(2))) throw new IOException("invalid icon file");
            return icon;
        } catch (Exception exception) { throw new IllegalStateException("BACKUP_BRANDING_INVALID", exception); }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
