package com.ledgame.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "ledgame.database-backup.enabled=false",
    "ledgame.legacy-compatibility.enabled=true",
    "ledgame.legacy-compatibility.port=0"
})
class LegacyHttpConnectorIntegrationTest {
    @LocalServerPort
    private int mainPort;

    @Autowired
    private ServletWebServerApplicationContext webServerContext;

    @Test
    void mainAndLegacyConnectorsServeTheSameApplication() throws Exception {
        TomcatWebServer webServer = (TomcatWebServer) webServerContext.getWebServer();
        int legacyPort = Arrays.stream(webServer.getTomcat().getService().findConnectors())
                .mapToInt(Connector::getLocalPort)
                .filter(port -> port > 0 && port != mainPort)
                .findFirst()
                .orElseThrow(() -> new AssertionError("legacy connector is not listening"));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> mainResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> legacyResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + legacyPort + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, mainResponse.statusCode());
        assertEquals(200, legacyResponse.statusCode());
        assertTrue(legacyResponse.body().contains("\"ok\":true"));

        HttpResponse<String> ping = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + legacyPort + "/dev/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ping.statusCode());
        assertTrue(ping.body().contains("\"data\":\"pong\""));
        HttpResponse<String> heartbeat = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + legacyPort + "/dev/gameCallback"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"cmd\":1}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, heartbeat.statusCode());
        assertTrue(heartbeat.body().contains("\"code\":200"));
    }
}
