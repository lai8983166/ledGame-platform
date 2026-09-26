package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

class LegacyHttpConnectorConfigurationTest {
    @Test
    void reportsTheLegacyPortWhenItIsAlreadyOccupied() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            LegacyHttpConnectorConfiguration configuration = new LegacyHttpConnectorConfiguration(port);

            assertThatThrownBy(() -> configuration.customize(new TomcatServletWebServerFactory()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TCP port " + port)
                    .hasMessageContaining("PLATFORM_LEGACY_COMPATIBILITY_PORT");
        }
    }
}
