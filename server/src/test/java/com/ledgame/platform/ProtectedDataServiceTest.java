package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProtectedDataServiceTest {
    private final byte[] key = Arrays.copyOf(
            "ledgame-test-data-key-material-32".getBytes(StandardCharsets.UTF_8), 32);

    @Test
    void usesRandomAuthenticatedEnvelopesAndPreservesUnicodeAndNulls() {
        ProtectedDataService service = service(key);
        String first = service.encrypt("members.name", "测试，会员\n甲");
        String second = service.encrypt("members.name", "测试，会员\n甲");

        assertThat(first).startsWith("enc:v1:").isNotEqualTo(second);
        assertThat(service.decrypt("members.name", first)).isEqualTo("测试，会员\n甲");
        String empty = service.encrypt("members.name", "");
        assertThat(empty).startsWith("enc:v1:");
        assertThat(service.decrypt("members.name", empty)).isEmpty();
        assertThat(service.encrypt("members.name", null)).isNull();
        assertThat(service.decrypt("members.name", null)).isNull();
    }

    @Test
    void rejectsWrongContextTamperingWrongKeyAndUnknownVersion() {
        ProtectedDataService service = service(key);
        String encrypted = service.encrypt("members.phone", "13800138000");

        assertThatThrownBy(() -> service.decrypt("members.name", encrypted))
                .hasMessage("DATA_PROTECTION_INTEGRITY_FAILED");
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.decrypt("members.phone", tampered))
                .hasMessage("DATA_PROTECTION_INTEGRITY_FAILED");
        assertThatThrownBy(() -> service(new byte[32]).decrypt("members.phone", encrypted))
                .hasMessage("DATA_PROTECTION_KEY_ID_MISMATCH");
        assertThatThrownBy(() -> service.decrypt("members.phone", encrypted.replace("enc:v1:", "enc:v9:")))
                .hasMessage("DATA_PROTECTION_INTEGRITY_FAILED");
    }

    @Test
    void usesStableKeyedLookupHashesWithoutRevealingTheIdentifier() {
        ProtectedDataService service = service(key);
        String phoneHash = service.phoneLookupHash("13800138000");
        assertThat(phoneHash).hasSize(64).doesNotContain("13800138000");
        assertThat(service.phoneLookupHash("13800138000")).isEqualTo(phoneHash);
        assertThat(service.wristbandLookupHash("13800138000")).isNotEqualTo(phoneHash);
    }

    private static ProtectedDataService service(byte[] rawKey) {
        DataKeyMaterial material = DataProtectionKeyManager.material(rawKey);
        DataProtectionKeyManager keyManager = mock(DataProtectionKeyManager.class);
        when(keyManager.loadOrCreate()).thenReturn(material);
        when(keyManager.loadExisting()).thenReturn(material);
        return new ProtectedDataService(keyManager);
    }
}
