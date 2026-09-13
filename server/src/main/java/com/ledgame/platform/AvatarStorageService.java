package com.ledgame.platform;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Stores optional registration photos outside SQLite as authenticated binary files. */
@Service
public class AvatarStorageService {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SIDE = 1024;
    private static final String CONTEXT = "members.avatar";
    private static final String PREFIX = "uploaded:";
    private final ProtectedDataService protectedData;
    private final Path root;

    public AvatarStorageService(
            ProtectedDataService protectedData,
            @Value("${spring.datasource.url}") String datasourceUrl) {
        this.protectedData = protectedData;
        this.root = DatabaseBackupEngine.databasePath(datasourceUrl).getParent().resolve("avatars");
    }

    public String store(String encoded, String mimeType) {
        if (encoded == null || encoded.isBlank()) return null;
        String normalizedMime = mimeType == null ? "" : mimeType.trim().toLowerCase();
        if (!List.of("image/jpeg", "image/png").contains(normalizedMime)) {
            throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_UNSUPPORTED_MEDIA", "头像仅支持 JPEG 或 PNG 图片");
        }
        String raw = encoded;
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
        if (raw.length() > MAX_BYTES * 2) {
            throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "AVATAR_TOO_LARGE", "头像文件不能超过 256 KB");
        }
        final byte[] plaintext;
        try {
            plaintext = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException exception) {
            throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_INVALID", "头像内容不是有效的图片数据");
        }
        if (plaintext.length == 0 || plaintext.length > MAX_BYTES) {
            throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "AVATAR_TOO_LARGE", "头像文件不能超过 256 KB");
        }
        validateImage(plaintext, normalizedMime);
        String id = UUID.randomUUID().toString();
        Path target = fileFor(id);
        Path temporary = target.resolveSibling(target.getFileName() + ".writing");
        try {
            Files.createDirectories(root);
            Files.write(temporary, protectedData.encryptBytes(CONTEXT, plaintext));
            move(temporary, target);
            return PREFIX + id;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new PlatformApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AVATAR_STORAGE_FAILED", "头像保存失败，请稍后重试");
        }
    }

    public byte[] read(String avatarId) {
        if (!isUploaded(avatarId)) return null;
        String id = avatarId.substring(PREFIX.length());
        if (!id.matches("[0-9a-fA-F-]{36}")) return null;
        Path file = fileFor(id);
        try {
            if (!Files.isRegularFile(file)) return null;
            return protectedData.decryptBytes(CONTEXT, Files.readAllBytes(file));
        } catch (IOException | RuntimeException exception) {
            throw new PlatformApiException(HttpStatus.SERVICE_UNAVAILABLE, "AVATAR_READ_FAILED", "头像暂时无法读取");
        }
    }

    public boolean isUploaded(String avatarId) {
        return avatarId != null && avatarId.startsWith(PREFIX);
    }

    public void delete(String avatarId) {
        if (!isUploaded(avatarId)) return;
        String id = avatarId.substring(PREFIX.length());
        if (!id.matches("[0-9a-fA-F-]{36}")) return;
        deleteQuietly(fileFor(id));
    }

    public Path root() { return root; }

    public List<Path> storedFiles() {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private void validateImage(byte[] bytes, String mimeType) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_INVALID", "头像内容不是有效的图片数据");
            boolean jpeg = bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
            boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
            if (("image/jpeg".equals(mimeType) && !jpeg) || ("image/png".equals(mimeType) && !png)) {
                throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_MEDIA_MISMATCH", "头像类型与实际图片内容不一致");
            }
            if (image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
                throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_EXCEEDED", "头像尺寸不能超过 1024×1024");
            }
        } catch (IOException exception) {
            throw invalid(HttpStatus.BAD_REQUEST, "AVATAR_INVALID", "头像内容不是有效的图片数据");
        }
    }

    private Path fileFor(String id) { return root.resolve(id + ".bin").normalize(); }

    private static PlatformApiException invalid(HttpStatus status, String code, String message) {
        return new PlatformApiException(status, code, message);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
