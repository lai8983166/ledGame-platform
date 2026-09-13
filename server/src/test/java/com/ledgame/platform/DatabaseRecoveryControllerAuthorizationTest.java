package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DatabaseRecoveryControllerAuthorizationTest {
    @Test
    void recoveryEndpointsRequireFactoryAndLoopback() {
        DatabaseRecoveryService service = mock(DatabaseRecoveryService.class);
        OperatorAuthorizationService authorization = mock(OperatorAuthorizationService.class);
        DatabaseRecoveryController controller = new DatabaseRecoveryController(service, authorization);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authorization.requireFactory(9L)).thenThrow(new PlatformApiException(HttpStatus.FORBIDDEN,
                BackupErrorCode.IMPORT_FORBIDDEN.name(), BackupErrorCode.IMPORT_FORBIDDEN.defaultMessage()));

        assertThatThrownBy(() -> controller.createRequest(request, 9L,
                new DatabaseRecoveryController.PathRequest("C:/backup")))
                .isInstanceOf(PlatformApiException.class);
        assertThatThrownBy(() -> controller.importResponse(request, 9L,
                new DatabaseRecoveryController.ResponseImportRequest("C:/backup", "C:/response.json")))
                .isInstanceOf(PlatformApiException.class);
        verify(service, never()).createRequest(org.mockito.ArgumentMatchers.any());
        verify(service, never()).importResponse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void remoteRecoveryRequestIsRejectedBeforeAuthorization() {
        DatabaseRecoveryController controller = new DatabaseRecoveryController(mock(DatabaseRecoveryService.class),
                mock(OperatorAuthorizationService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.20");
        assertThatThrownBy(() -> controller.createRequest(request, 1L,
                new DatabaseRecoveryController.PathRequest("C:/backup")))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("LOCAL_REQUEST_REQUIRED"));
    }
}
