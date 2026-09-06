package com.ledgame.platform;

import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivationServiceTest {
    @TempDir Path directory;
    @Test void missingIdentityOrPublicKeyIsAnExplicitFailure() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var noIdentity = new ActivationService(directory, () -> { throw ActivationCode.error("ACTIVATION_MACHINE_ID_UNAVAILABLE", "无法读取机器身份"); }, keys::getPublic);
        assertFalse(noIdentity.activated());
        assertEquals("ACTIVATION_MACHINE_ID_UNAVAILABLE", noIdentity.status().errorCode());
        var noKey = new ActivationService(directory, () -> "machine", () -> { throw ActivationCode.error("ACTIVATION_PUBLIC_KEY_MISSING", "缺少公钥"); });
        assertFalse(noKey.activated());
        assertEquals("ACTIVATION_PUBLIC_KEY_MISSING", noKey.status().errorCode());
    }
    @Test void persistsIndependentlyAndInvalidCodeNeverOverwrites() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String machine = WindowsMachineIdentity.machineCode("12345678-1234-1234-1234-123456789abc");
        var service = new ActivationService(directory.resolve("activation"), () -> machine, keys::getPublic);
        var started = new AtomicInteger(); service.whenActivated(started::incrementAndGet);
        assertFalse(service.activated()); assertEquals(0, started.get());
        var code = ActivatedTestConfiguration.code(keys, machine);
        service.activate(code); service.activate(code);
        assertEquals(1, started.get());
        byte[] saved = Files.readAllBytes(directory.resolve("activation/license.json"));
        // 模拟原子替换前进程中断留下的临时文件：重启只认已提交的 license.json。
        Files.writeString(directory.resolve("activation/license-interrupted.tmp"), "partial-write");
        assertTrue(new ActivationService(directory.resolve("activation"), () -> machine, keys::getPublic).activated());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("activation/license.json")));
        assertThrows(PlatformApiException.class, () -> service.activate(code + "!"));
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("activation/license.json")));
        Files.writeString(directory.resolve("platform.db"), "replacement-database");
        assertTrue(new ActivationService(directory.resolve("activation"), () -> machine, keys::getPublic).activated());
        var other = new ActivationService(directory.resolve("activation"), () -> "other", keys::getPublic);
        assertFalse(other.activated()); assertEquals("ACTIVATION_MACHINE_MISMATCH", other.status().errorCode());
        Files.writeString(directory.resolve("unwritable"), "not-a-directory");
        var unwritable = new ActivationService(directory.resolve("unwritable"), () -> machine, keys::getPublic);
        assertThrows(PlatformApiException.class, () -> unwritable.activate(code));
        assertFalse(unwritable.activated());
    }
    @Test void notActivatedTakesPrecedenceOverReadyDatabase() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var service = new ActivationService(directory, () -> "machine", keys::getPublic);
        var gate = new StartupGate(); gate.update(StartupGate.degraded(BackupErrorCode.BACKUP_DISABLED, null, null, 0, null));
        var interceptor = new StartupGateInterceptor(gate, service);
        for (String path : new String[]{"/api/members", "/api/operator-auth/login", "/api/wristbands/charge", "/api/game-plays/start"}) {
            var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
            var error = assertThrows(PlatformApiException.class, () -> interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), new Object()));
            assertEquals("PLATFORM_NOT_ACTIVATED", error.getCode());
        }
        var backup = mock(DatabaseBackupCoordinator.class);
        new ActivationStartupRunner(service, backup).run(null);
        verify(backup, never()).startAfterActivation();
    }
    @Test void localOnlyController() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var controller = new ActivationController(new ActivationService(directory, () -> "machine", keys::getPublic));
        var request = new org.springframework.mock.web.MockHttpServletRequest(); request.setRemoteAddr("192.168.1.2");
        assertThrows(PlatformApiException.class, () -> controller.status(request));
        request.setRemoteAddr("127.0.0.1"); assertFalse(controller.status(request).activated());
    }
}
