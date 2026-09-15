package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DataProtectionKeyManagerTest {
    @TempDir Path root;

    @Test
    void explicitTestKeyNeverCreatesOrReadsProductionEnvelope() {
        DataProtectionProperties properties = new DataProtectionProperties();
        properties.setTestKeyBase64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        Path keyPath = root.resolve("isolated/data-key.dpapi");
        properties.setKeyPath(keyPath.toString());
        DataProtectionKeyManager manager = new DataProtectionKeyManager(
                properties, new WindowsDataProtector(), new ObjectMapper(),
                "jdbc:sqlite:" + root.resolve("platform.db"));

        assertThat(manager.loadOrCreate().keyId()).isEqualTo(manager.loadExisting().keyId());
        assertThat(keyPath).doesNotExist();
    }

    @Test
    void explicitTestKeyNeverPublishesAStaleDpapiEnvelope() throws Exception {
        DataProtectionProperties properties = new DataProtectionProperties();
        properties.setTestKeyBase64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        Path keyPath = root.resolve("isolated/data-key.dpapi");
        properties.setKeyPath(keyPath.toString());
        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, "{\"format\":\"ledgame-data-key-v1\",\"keyId\":\"stale\",\"protectedKey\":\"stale\"}");
        DataProtectionKeyManager manager = new DataProtectionKeyManager(
                properties, new WindowsDataProtector(), new ObjectMapper(),
                "jdbc:sqlite:" + root.resolve("platform.db"));

        assertThat(manager.envelopeBytes()).isEmpty();
        assertThat(keyPath).exists();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsCurrentUserCanUnsealAfterRestartWithoutPlaintextKeyInFile() throws Exception {
        DataProtectionProperties properties = new DataProtectionProperties();
        Path keyPath = root.resolve("security/data-key.dpapi");
        properties.setKeyPath(keyPath.toString());
        ObjectMapper mapper = new ObjectMapper();
        DataProtectionKeyManager first = new DataProtectionKeyManager(
                properties, new WindowsDataProtector(), mapper,
                "jdbc:sqlite:" + root.resolve("platform.db"));
        DataKeyMaterial generated = first.loadOrCreate();

        DataProtectionKeyManager restarted = new DataProtectionKeyManager(
                properties, new WindowsDataProtector(), mapper,
                "jdbc:sqlite:" + root.resolve("platform.db"));
        assertThat(restarted.loadExisting().key()).isEqualTo(generated.key());
        String envelope = Files.readString(keyPath);
        assertThat(envelope).contains(DataProtectionKeyManager.ENVELOPE_FORMAT, generated.keyId())
                .doesNotContain(Base64.getEncoder().encodeToString(generated.key()));
    }
}
