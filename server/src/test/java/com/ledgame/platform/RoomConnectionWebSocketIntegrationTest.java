package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "spring.datasource.url=jdbc:sqlite:file:room-connection-test?mode=memory&cache=shared" })
class RoomConnectionWebSocketIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Test
    void connectsPublishesEventAndMarksProjectionOfflineAfterDisconnect() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler();
        WebSocketSession session = client.execute(handler,
                "ws://127.0.0.1:" + port + "/ws/rooms").get();
        try {
            session.sendMessage(new TextMessage("""
                    {"type":"HELLO"}
                    """));
            session.sendMessage(new TextMessage("""
                    {"type":"ROOM_SNAPSHOT","eventId":"integration-game-1","sequence":1,"state":{"engineState":"IDLE","queueSummary":{"waiting":[{}]}}}
                    """));

            Map<String, Object> online = awaitRoom(room -> Boolean.TRUE.equals(room.get("online"))
                    && "127.0.0.1".equals(room.get("ip"))
                    && Integer.valueOf(1).equals(room.get("queueLength")));
            assertThat(online).containsEntry("queueLength", 1);
            session.sendMessage(new TextMessage("""
                    {"type":"GAME_TIMING_CHANGED","eventId":"integration-game-2","sequence":2,"state":{"engineState":"RUNNING","gameName":"Color Rush","gameTime":{"mode":"UNLIMITED","remainingMillis":null,"running":true}}}
                    """));
            Map<String, Object> timed = awaitRoom(room -> "GAME_TIMING_CHANGED".equals(room.get("lastEventType")));
            assertThat((Map<String, Object>) ((Map<String, Object>) timed.get("state")).get("gameTime"))
                    .containsEntry("mode", "UNLIMITED")
                    .containsEntry("running", true);
            ResponseEntity<Map> renamed = http.exchange(
                    "/api/rooms/127.0.0.1", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("roomName", "测试房间")), Map.class);
            assertThat(renamed.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(awaitRoom(room -> "测试房间".equals(room.get("roomName"))))
                    .containsEntry("online", true);

            session.close();
            Map<String, Object> offline = awaitRoom(room -> Boolean.FALSE.equals(room.get("online")));
            assertThat(offline).containsEntry("lastSequence", 2);
            assertThat(offline).containsEntry("roomName", "测试房间");
        } finally {
            if (session.isOpen()) session.close();
        }
    }

    @Test
    void sendsCurrentChildModeOnWelcomeAndBroadcastsUpdates() throws Exception {
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messages.add(message.getPayload());
            }
        };
        WebSocketSession session = new StandardWebSocketClient().execute(
                handler, "ws://127.0.0.1:" + port + "/ws/rooms").get();
        try {
            session.sendMessage(new TextMessage("{\"type\":\"HELLO\"}"));
            assertThat(messages.poll(3, TimeUnit.SECONDS))
                    .contains("\"type\":\"WELCOME\"", "\"childMode\":false");

            ResponseEntity<Map> updated = http.exchange(
                    "/api/feature-settings/child-mode", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("enabled", true)), Map.class);
            assertThat(updated.getBody()).containsEntry("childMode", true);
            assertThat(messages.poll(3, TimeUnit.SECONDS))
                    .contains("CHILD_MODE_CHANGED", "\"childMode\":true");
        } finally {
            session.close();
            http.exchange("/api/feature-settings/child-mode", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("enabled", false)), Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitRoom(Predicate<Map<String, Object>> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ResponseEntity<List<Map<String, Object>>> response = http.getForEntity(
                    "/api/rooms", (Class<List<Map<String, Object>>>) (Class<?>) List.class);
            if (response.getBody() != null) {
                for (Map<String, Object> room : response.getBody()) {
                    if (predicate.test(room)) return room;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for room projection");
    }
}
