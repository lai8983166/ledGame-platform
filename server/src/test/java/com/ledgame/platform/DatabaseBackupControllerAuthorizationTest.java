package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

class DatabaseBackupControllerAuthorizationTest {
    @Test
    void candidateDiscoveryAndImportPreparationRequireFactoryAuthorization() {
        DatabaseBackupCoordinator coordinator = mock(DatabaseBackupCoordinator.class);
        OperatorAuthorizationService authorization = mock(OperatorAuthorizationService.class);
        DatabaseImportService imports = mock(DatabaseImportService.class);
        DatabaseBackupController controller = new DatabaseBackupController(coordinator, authorization, imports);
        HttpServletRequest request = loopback();
        PlatformApiException forbidden = new PlatformApiException(HttpStatus.FORBIDDEN,
                BackupErrorCode.IMPORT_FORBIDDEN.name(), BackupErrorCode.IMPORT_FORBIDDEN.defaultMessage());
        when(authorization.requireFactory(7L)).thenThrow(forbidden);

        assertThatThrownBy(() -> controller.candidates(request, 7L))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IMPORT_FORBIDDEN"));
        assertThatThrownBy(() -> controller.prepareImport(request, "candidate", 7L))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IMPORT_FORBIDDEN"));
        verify(imports, never()).discoverFixedCandidates();
        verify(imports, never()).prepare("candidate");
        assertThatThrownBy(() -> controller.keepCurrentDatabase(request, 7L))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IMPORT_FORBIDDEN"));
        verify(coordinator, never()).keepCurrentDatabase();
    }

    @Test
    void statusStillRequiresAnEnabledLoggedInOperator() {
        DatabaseBackupCoordinator coordinator = mock(DatabaseBackupCoordinator.class);
        OperatorAuthorizationService authorization = mock(OperatorAuthorizationService.class);
        DatabaseImportService imports = mock(DatabaseImportService.class);
        DatabaseBackupController controller = new DatabaseBackupController(coordinator, authorization, imports);
        HttpServletRequest request = loopback();
        BackupStatusSnapshot expected = StartupGate.degraded(
                BackupErrorCode.NO_CROSS_DISK_TARGET, null, null, 1, null);
        when(coordinator.status()).thenReturn(expected);

        assertThat(controller.backupStatus(request, 1L)).isSameAs(expected);
        verify(authorization).require(1L);
    }

    @Test
    void remoteStatusRequestsAreRejectedBeforeDatabaseDetailsAreReturned() {
        DatabaseBackupController controller = new DatabaseBackupController(mock(DatabaseBackupCoordinator.class),
                mock(OperatorAuthorizationService.class), mock(DatabaseImportService.class));
        HttpServletRequest remote = mock(HttpServletRequest.class);
        when(remote.getRemoteAddr()).thenReturn("192.168.1.20");
        assertThatThrownBy(() -> controller.backupStatus(remote, 1L))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("LOCAL_REQUEST_REQUIRED"));
    }

    private static HttpServletRequest loopback() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }
}
