package com.ledgame.platform;

import java.nio.file.*;
import java.security.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:", "ledgame.database-backup.enabled=false" })
@Import(ActivationApiIntegrationTest.Fixture.class)
class ActivationApiIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivationService activation;
    @TestConfiguration static class Fixture implements org.springframework.beans.factory.DisposableBean {
        static KeyPair keys;
        static final String MACHINE = WindowsMachineIdentity.machineCode("87654321-1234-1234-1234-123456789abc");
        Path directory;
        @Bean @Primary ActivationService unactivatedFixture() throws Exception {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            directory = Files.createTempDirectory("ledgame-activation-api-");
            return new ActivationService(directory, () -> MACHINE, keys::getPublic);
        }
        @Override public void destroy() throws Exception {
            Files.deleteIfExists(directory.resolve("license.json")); Files.deleteIfExists(directory);
        }
    }
    @Test void activationPrecedesDatabaseChecksAndBlocksAllBusiness() throws Exception {
        assertFalse(activation.activated());
        var socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(java.net.URI.create(http.getRootUri().replace("http:", "ws:") + "/ws/rooms"),
                        new java.net.http.WebSocket.Listener() {});
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> socket.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(java.net.http.WebSocketHandshakeException.class, failure.getCause());
        assertEquals(503, ((java.net.http.WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
        assertEquals("CHECKING", http.getForObject("/api/system/startup-status", Map.class).get("state"));
        assertEquals(false, http.getForObject("/api/health", Map.class).get("businessReady"));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class);
        for (String path : new String[]{"/api/members", "/api/wristbands/charge", "/api/wristbands/bind", "/api/game-plays/start", "/api/operator-auth/login"}) {
            var result = http.postForEntity(path, Map.of(), Map.class);
            assertEquals(503, result.getStatusCode().value());
            assertEquals("PLATFORM_NOT_ACTIVATED", result.getBody().get("code"));
        }
        assertEquals(503, http.getForEntity("/api/members", Map.class).getStatusCode().value());
        var settlement = http.exchange("/api/game-plays/1/result", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of()), Map.class);
        assertEquals(503, settlement.getStatusCode().value());
        assertEquals("PLATFORM_NOT_ACTIVATED", settlement.getBody().get("code"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class));
        String code = ActivatedTestConfiguration.code(Fixture.keys, Fixture.MACHINE);
        assertEquals(400, http.postForEntity("/api/system/activation", Map.of("code", code + "!"), Map.class).getStatusCode().value());
        assertFalse(activation.activated());
        assertEquals(200, http.postForEntity("/api/system/activation", Map.of("code", code), Map.class).getStatusCode().value());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                Boolean.TRUE.equals(http.getForObject("/api/health", Map.class).get("businessReady")));
        assertEquals(200, http.getForEntity("/api/members", String.class).getStatusCode().value());
    }
}
