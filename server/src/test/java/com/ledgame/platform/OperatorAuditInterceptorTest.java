package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OperatorAuditInterceptorTest {
    @Test
    void recoveryEndpointsAreMappedAndCarryRequestIdIntoSuccessfulAudit() throws Exception {
        OperatorActionLogService logs = mock(OperatorActionLogService.class);
        OperatorSnapshot operator = new OperatorSnapshot(7L, "factory", "Factory");
        when(logs.resolve(7L)).thenReturn(operator);
        OperatorAuditInterceptor interceptor = new OperatorAuditInterceptor(logs);

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/database-recovery/request");
        request.addHeader("X-Operator-Id", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        request.setAttribute(OperatorAuditInterceptor.TARGET_ID_ATTRIBUTE, "request-123");
        response.setStatus(201);

        interceptor.afterCompletion(request, response, new Object(), null);

        var action = org.mockito.ArgumentCaptor.forClass(OperatorAuditAction.class);
        verify(logs).record(eq(operator), action.capture(), eq("POST"),
                eq("/api/database-recovery/request"));
        assertThat(action.getValue().action()).isEqualTo("DATABASE_RECOVERY_REQUEST_CREATED");
        assertThat(action.getValue().targetType()).isEqualTo("DATABASE_RECOVERY");
        assertThat(action.getValue().targetId()).isEqualTo("request-123");
    }

    @Test
    void recoveryFailureAndForbiddenResponsesArePersistedAsDistinctActions() throws Exception {
        OperatorActionLogService logs = mock(OperatorActionLogService.class);
        OperatorSnapshot operator = new OperatorSnapshot(7L, "factory", "Factory");
        when(logs.resolve(7L)).thenReturn(operator);
        OperatorAuditInterceptor interceptor = new OperatorAuditInterceptor(logs);

        MockHttpServletRequest failedRequest = new MockHttpServletRequest(
                "POST", "/api/database-recovery/response/import");
        failedRequest.addHeader("X-Operator-Id", "7");
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        interceptor.preHandle(failedRequest, failedResponse, new Object());
        failedResponse.setStatus(422);
        interceptor.afterCompletion(failedRequest, failedResponse, new Object(), null);

        MockHttpServletRequest deniedRequest = new MockHttpServletRequest(
                "POST", "/api/database-recovery/request/cancel");
        deniedRequest.addHeader("X-Operator-Id", "7");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        interceptor.preHandle(deniedRequest, deniedResponse, new Object());
        deniedResponse.setStatus(403);
        interceptor.afterCompletion(deniedRequest, deniedResponse, new Object(), null);

        var actions = org.mockito.ArgumentCaptor.forClass(OperatorAuditAction.class);
        verify(logs, org.mockito.Mockito.times(2)).record(eq(operator), actions.capture(),
                eq("POST"), any());
        assertThat(actions.getAllValues()).extracting(OperatorAuditAction::action)
                .containsExactlyInAnyOrder(
                        "DATABASE_RECOVERY_RESPONSE_IMPORTED_FAILED",
                        "DATABASE_RECOVERY_REQUEST_CANCELLED_DENIED");
    }

    @Test
    void memberAndStoreSettingsMutationsAreMappedToAuditableActions() throws Exception {
        OperatorActionLogService logs = mock(OperatorActionLogService.class);
        OperatorSnapshot operator = new OperatorSnapshot(7L, "factory", "Factory");
        when(logs.resolve(7L)).thenReturn(operator);
        OperatorAuditInterceptor interceptor = new OperatorAuditInterceptor(logs);

        MockHttpServletRequest memberRequest = new MockHttpServletRequest("PUT", "/api/members/42");
        memberRequest.addHeader("X-Operator-Id", "7");
        MockHttpServletResponse memberResponse = new MockHttpServletResponse();
        interceptor.preHandle(memberRequest, memberResponse, new Object());
        memberResponse.setStatus(200);
        interceptor.afterCompletion(memberRequest, memberResponse, new Object(), null);

        MockHttpServletRequest settingsRequest = new MockHttpServletRequest("PATCH", "/api/store-settings");
        settingsRequest.addHeader("X-Operator-Id", "7");
        MockHttpServletResponse settingsResponse = new MockHttpServletResponse();
        interceptor.preHandle(settingsRequest, settingsResponse, new Object());
        settingsResponse.setStatus(200);
        interceptor.afterCompletion(settingsRequest, settingsResponse, new Object(), null);

        var actions = org.mockito.ArgumentCaptor.forClass(OperatorAuditAction.class);
        verify(logs, org.mockito.Mockito.times(2)).record(eq(operator), actions.capture(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(actions.getAllValues()).extracting(OperatorAuditAction::action)
                .containsExactlyInAnyOrder("MEMBER_UPDATED", "STORE_SETTINGS_UPDATED");
    }
}
