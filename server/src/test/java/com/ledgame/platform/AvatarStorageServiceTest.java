package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvatarStorageServiceTest {
    @TempDir Path temp;

    @Test
    void storesEncryptedPhotoWithOpaqueIdentifierAndReadsItBack() throws Exception {
        ProtectedDataService protection = service();
        AvatarStorageService avatars = new AvatarStorageService(protection, "jdbc:sqlite:" + temp.resolve("platform.db"));
        byte[] image = image("jpg", 8, 4);
        String id = avatars.store(Base64.getEncoder().encodeToString(image), "image/jpeg");
        assertThat(id).startsWith("uploaded:").doesNotContain(temp.toString());
        Path stored = avatars.storedFiles().get(0);
        assertThat(Files.readAllBytes(stored)[0]).isZero();
        assertThat(avatars.read(id)).containsExactly(image);
    }

    @Test
    void rejectsTamperedEncryptedPhotoWithControlledReadError() throws Exception {
        AvatarStorageService avatars = new AvatarStorageService(service(), "jdbc:sqlite:" + temp.resolve("platform.db"));
        String id = avatars.store(Base64.getEncoder().encodeToString(image("jpg", 2, 2)), "image/jpeg");
        Files.write(avatars.storedFiles().get(0), new byte[] { 1, 2, 3 });
        assertThatThrownBy(() -> avatars.read(id)).hasMessageContaining("头像暂时无法读取");
    }

    @Test
    void rejectsUnsupportedAndOversizedPayloadsBeforeWritingAFile() throws Exception {
        AvatarStorageService avatars = new AvatarStorageService(service(), "jdbc:sqlite:" + temp.resolve("platform.db"));
        assertThatThrownBy(() -> avatars.store("AAAA", "text/plain")).hasMessageContaining("头像仅支持");
        assertThatThrownBy(() -> avatars.store("AAAA", "image/jpeg")).hasMessageContaining("有效的图片数据");
        byte[] png = image("png", 1, 1);
        assertThatThrownBy(() -> avatars.store(Base64.getEncoder().encodeToString(png), "image/jpeg"))
                .hasMessageContaining("类型与实际图片内容不一致");
        String oversized = Base64.getEncoder().encodeToString(new byte[AvatarStorageService.MAX_BYTES + 1]);
        assertThatThrownBy(() -> avatars.store(oversized, "image/jpeg")).hasMessageContaining("不能超过");
        assertThat(avatars.read("uploaded:../../outside")).isNull();
        assertThat(avatars.storedFiles()).isEmpty();
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }

    private static ProtectedDataService service() {
        byte[] raw = Arrays.copyOf("ledgame-avatar-test-key-material-32".getBytes(StandardCharsets.UTF_8), 32);
        DataKeyMaterial material = DataProtectionKeyManager.material(raw);
        DataProtectionKeyManager manager = mock(DataProtectionKeyManager.class);
        when(manager.loadOrCreate()).thenReturn(material);
        when(manager.loadExisting()).thenReturn(material);
        return new ProtectedDataService(manager);
    }
}
