package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class RoomConnectionRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RoomConnectionProperties properties;
    private RoomConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new RoomConnectionProperties();
        properties.setToken("secret");
        registry = new RoomConnectionRegistry(properties, objectMapper);
    }

    @Test
    void acceptsHelloAndAppliesOnlyNewSequences() throws Exception {
        WebSocketSession session = session("session-a", "192.168.1.25");
        registry.register(session, objectMapper.readTree("""
            {"type":"HELLO","token":"secret","deviceId":"game-01","roomId":"room-01"}
            """));

        var first = registry.accept(session, objectMapper.readTree("""
            {"type":"ROOM_SNAPSHOT","eventId":"event-1","sequence":1,"state":{"engineState":"IDLE","queueSummary":{"waiting":[]}}}
            """));
        var duplicate = registry.accept(session, objectMapper.readTree("""
            {"type":"QUEUE_CHANGED","eventId":"event-1","sequence":1,"state":{"engineState":"RUNNING","queueSummary":{"waiting":[{}]}}}
            """));

        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(registry.list()).singleElement().satisfies(room -> {
            assertThat(room).containsEntry("ip", "192.168.1.25");
            assertThat(room).containsEntry("lastSequence", 1L);
            assertThat(room).containsEntry("queueLength", 0);
        });
    }

    @Test
    void rejectsInvalidHelloAndUnsupportedEvents() throws Exception {
        WebSocketSession session = session("session-b", "192.168.1.26");
        assertThatThrownBy(() -> registry.register(session, objectMapper.readTree(
                "{\"type\":\"HELLO\",\"token\":\"wrong\"}")))
                .hasMessageContaining("ROOM_CONNECTION_UNAUTHORIZED");

        registry.register(session, objectMapper.readTree(
                "{\"type\":\"HELLO\",\"token\":\"secret\"}"));
        assertThatThrownBy(() -> registry.accept(session, objectMapper.readTree(
                "{\"type\":\"UNKNOWN\",\"sequence\":1}")))
                .hasMessageContaining("ROOM_EVENT_UNSUPPORTED");
    }

    @Test
    void preservesRoomProjectionAsOfflineAfterDisconnect() throws Exception {
        WebSocketSession session = session("session-c", "192.168.1.27");
        registry.register(session, objectMapper.readTree(
                "{\"type\":\"HELLO\",\"token\":\"secret\",\"roomId\":\"room-03\"}"));
        registry.accept(session, objectMapper.readTree(
                "{\"type\":\"ROOM_SNAPSHOT\",\"sequence\":1,\"state\":{\"engineState\":\"IDLE\"}}"));

        registry.unregister(session);

        assertThat(registry.list()).singleElement().satisfies(room -> {
            assertThat(room).containsEntry("ip", "192.168.1.27");
            assertThat(room).containsEntry("online", false);
        });
        assertThat(registry.find("192.168.1.27")).containsEntry("online", false);
    }

    private static WebSocketSession session(String id, String ip) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 12345));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
