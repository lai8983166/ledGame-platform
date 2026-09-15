package com.ledgame.platform;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Stores the optional runtime member-admin icon outside SQLite. */
@Service
public class BrandingStorageService {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SIDE = 1024;
    private static final String DIRECTORY = "branding";
    private final Path root;

    public BrandingStorageService(
            @Value("${spring.datasource.url}") String datasourceUrl) {
        this.root = DatabaseBackupEngine.databasePath(datasourceUrl).getParent().resolve(DIRECTORY);
    }

    public StoredIcon store(String encoded, String mimeType) {
        String normalizedMime = mimeType == null ? "" : mimeType.trim().toLowerCase();
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(normalizedMime)) {
            throw invalid(HttpStatus.BAD_REQUEST, "BRANDING_ICON_UNSUPPORTED_MEDIA", "应用图标仅支持 JPEG、PNG 或 WebP 图片");
        }
        String raw = encoded == null ? "" : encoded.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
        if (raw.length() > MAX_BYTES * 2) {
            throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "BRANDING_ICON_TOO_LARGE", "应用图标不能超过 256 KB");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException exception) {
            throw invalid(HttpStatus.BAD_REQUEST, "BRANDING_ICON_INVALID", "应用图标不是有效的图片数据");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "BRANDING_ICON_TOO_LARGE", "应用图标不能超过 256 KB");
        }
        validateImage(bytes, normalizedMime);
        String extension = extension(normalizedMime);
        String relativePath = DIRECTORY + "/app-icon." + extension;
        Path target = root.resolve("app-icon." + extension).normalize();
        Path temporary = target.resolveSibling(target.getFileName() + ".writing");
        try {
            Files.createDirectories(root);
            Files.write(temporary, bytes);
            move(temporary, target);
            for (String other : List.of("png", "jpg", "webp")) {
                if (!other.equals(extension)) Files.deleteIfExists(root.resolve("app-icon." + other));
            }
            return new StoredIcon(relativePath, sha256(bytes), normalizedMime, bytes);
        } catch (IOException exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new PlatformApiException(HttpStatus.INTERNAL_SERVER_ERROR, "BRANDING_ICON_STORAGE_FAILED", "应用图标保存失败，请稍后重试");
        }
    }

    public StoredIcon read(String relativePath) {
        if (relativePath == null || !relativePath.matches("branding/app-icon\\.(png|jpg|webp)")) return null;
        Path path = root.resolve(Path.of(relativePath).getFileName()).normalize();
        try {
            if (!Files.isRegularFile(path)) return null;
            byte[] bytes = Files.readAllBytes(path);
            String extension = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1);
            return new StoredIcon(relativePath, sha256(bytes), mime(extension), bytes);
        } catch (IOException exception) {
            return null;
        }
    }

    public void delete(String relativePath) {
        StoredIcon icon = read(relativePath);
        if (icon == null) return;
        try { Files.deleteIfExists(root.resolve(Path.of(relativePath).getFileName())); }
        catch (IOException ignored) { }
    }

    public Path root() { return root; }

    private static void validateImage(byte[] bytes, String mimeType) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                    || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
                throw invalid(HttpStatus.BAD_REQUEST, "BRANDING_ICON_INVALID", "应用图标尺寸不能超过 1024×1024，且必须是有效图片");
            }
            boolean jpeg = bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
            boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
            if (("image/jpeg".equals(mimeType) && !jpeg) || ("image/png".equals(mimeType) && !png)) {
                throw invalid(HttpStatus.BAD_REQUEST, "BRANDING_ICON_MEDIA_MISMATCH", "应用图标类型与实际图片内容不一致");
            }
        } catch (IOException exception) {
            throw invalid(HttpStatus.BAD_REQUEST, "BRANDING_ICON_INVALID", "应用图标不是有效的图片数据");
        }
    }

    private static String extension(String mime) {
        return switch (mime) { case "image/jpeg" -> "jpg"; case "image/webp" -> "webp"; default -> "png"; };
    }

    private static String mime(String extension) {
        return switch (extension) { case "jpg" -> "image/jpeg"; case "webp" -> "image/webp"; default -> "image/png"; };
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("BRANDING_ICON_HASH_FAILED", exception); }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static PlatformApiException invalid(HttpStatus status, String code, String message) {
        return new PlatformApiException(status, code, message);
    }

    public record StoredIcon(String path, String sha256, String mimeType, byte[] bytes) {}
}
