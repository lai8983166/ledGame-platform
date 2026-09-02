package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class StartupGateInterceptorTest {
    private final StartupGate gate = new StartupGate();
    private final StartupGateInterceptor interceptor = new StartupGateInterceptor(gate);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    void checkingAllowsOnlyHealthLoginAndReadOnlyStartupStatus() throws Exception {
        assertThat(interceptor.preHandle(request("/api/health"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/system/startup-status"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/operator-auth/login"), response, new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request("/api/members"), response, new Object()))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PLATFORM_STARTUP_CHECKING"));
    }

    @Test
    void maintenanceAllowsBackupManagementButBlocksNormalBusiness() throws Exception {
        gate.update(StartupGate.maintenance(BackupErrorCode.DATABASE_VERSION_CONFLICT, "B:\\", 2, 3L));
        assertThat(interceptor.preHandle(request("/api/database-backup/status"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/database-backup/candidates"), response, new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request("/api/wristbands/charge"), response, new Object()))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PLATFORM_MAINTENANCE_LOGIN_REQUIRED"));
    }

    @Test
    void degradedModeDoesNotBlockExistingBusinessOperations() throws Exception {
        gate.update(StartupGate.degraded(BackupErrorCode.NO_CROSS_DISK_TARGET, null, null, 4, null));
        assertThat(interceptor.preHandle(request("/api/members"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/wristbands/charge"), response, new Object())).isTrue();
    }

    private static HttpServletRequest request(String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }
}
