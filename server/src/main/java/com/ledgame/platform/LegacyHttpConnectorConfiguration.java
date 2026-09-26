package com.ledgame.platform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "ledgame.legacy-compatibility",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LegacyHttpConnectorConfiguration
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyHttpConnectorConfiguration.class);

    private final int legacyPort;

    public LegacyHttpConnectorConfiguration(
            @Value("${ledgame.legacy-compatibility.port:16668}") int legacyPort) {
        this.legacyPort = legacyPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        verifyPortAvailable();
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(legacyPort);
        connector.setScheme("http");
        connector.setSecure(false);
        LOG.info("Configuring legacy game-client HTTP listener on port {}", legacyPort);
        factory.addAdditionalTomcatConnectors(connector);
    }

    private void verifyPortAvailable() {
        if (legacyPort == 0) return;
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(legacyPort));
        } catch (IOException exception) {
            throw new IllegalStateException("Legacy game-client compatibility listener cannot bind TCP port "
                    + legacyPort + "; stop the process using this port or set PLATFORM_LEGACY_COMPATIBILITY_PORT "
                    + "to another free port (legacy clients must be configured for the same port).", exception);
        }
    }
}
