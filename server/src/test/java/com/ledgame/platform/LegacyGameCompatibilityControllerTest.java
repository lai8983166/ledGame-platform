package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class LegacyGameCompatibilityControllerTest {
    @Test
    void rejectedCallbackLogsCommandAndRoomCorrelationWithoutRawUid() {
        String uid = "2283055618";
        LegacyGameCompatibilityService service = mock(LegacyGameCompatibilityService.class);
        when(service.handle(any(), eq("192.0.2.42"))).thenThrow(new PlatformApiException(
                HttpStatus.BAD_REQUEST, "WRISTBAND_NOT_FOUND", "Unknown wristband"));
        LegacyGameCompatibilityController controller = new LegacyGameCompatibilityController(service, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.42");
        Logger logger = (Logger) LoggerFactory.getLogger(LegacyGameCompatibilityController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = controller.callback("""
                    {"cmd":5,"json":{"icList":["2283055618"]}}
                    """, request);

            assertThat(response.code()).isEqualTo(400);
            String diagnostics = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(diagnostics).contains("cmd=5", "roomIp=192.0.2.42", "correlationId=", "WRISTBAND_NOT_FOUND")
                    .doesNotContain(uid, "icList", "phone");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
