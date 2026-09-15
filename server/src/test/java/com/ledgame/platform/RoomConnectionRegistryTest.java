package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.mockito.ArgumentCaptor;

class RoomConnectionRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RoomConnectionProperties properties;
    private RoomConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new RoomConnectionProperties();
        registry = new RoomConnectionRegistry(properties, objectMapper);
    }

    @Test
    void acceptsHelloAndAppliesOnlyNewSequences() throws Exception {
        WebSocketSession session = session("session-a", "192.168.1.25");
        registry.register(session, objectMapper.readTree("""
            {"type":"HELLO","deviceId":"game-01","roomId":"room-01"}
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
    void acceptsHardwareStatusEventsAndStoresTheReportedState() throws Exception {
        WebSocketSession session = session("session-hardware", "192.168.1.29");
        registry.register(session, objectMapper.readTree("{\"type\":\"HELLO\"}"));

        registry.accept(session, objectMapper.readTree("""
                {"type":"HARDWARE_STATUS_CHANGED","sequence":1,"state":{
                  "engineState":"IDLE",
                  "hardware":[{"id":"elc408-controller","status":"online","foundControllers":1}]
                }}
                """));

        assertThat(registry.find("192.168.1.29")).satisfies(room -> {
            assertThat(room).containsEntry("lastEventType", "HARDWARE_STATUS_CHANGED");
            assertThat(((Map<?, ?>) room.get("state")).containsKey("hardware")).isTrue();
        });
    }

    @Test
    void acceptsCredentialFreeHelloAndRejectsUnsupportedEvents() throws Exception {
        WebSocketSession session = session("session-b", "192.168.1.26");
        registry.register(session, objectMapper.readTree(
                "{\"type\":\"HELLO\"}"));
        assertThatThrownBy(() -> registry.accept(session, objectMapper.readTree(
                "{\"type\":\"UNKNOWN\",\"sequence\":1}")))
                .hasMessageContaining("ROOM_EVENT_UNSUPPORTED");
    }

    @Test
    void preservesRoomProjectionAsOfflineAfterDisconnect() throws Exception {
        WebSocketSession session = session("session-c", "192.168.1.27");
        registry.register(session, objectMapper.readTree(
                "{\"type\":\"HELLO\",\"roomId\":\"room-03\"}"));
        registry.accept(session, objectMapper.readTree(
                "{\"type\":\"ROOM_SNAPSHOT\",\"sequence\":1,\"state\":{\"engineState\":\"IDLE\"}}"));

        registry.unregister(session);

        assertThat(registry.list()).singleElement().satisfies(room -> {
            assertThat(room).containsEntry("ip", "192.168.1.27");
            assertThat(room).containsEntry("online", false);
        });
        assertThat(registry.find("192.168.1.27")).containsEntry("online", false);
    }

    @Test
    void broadcastsChildModeToConnectedRooms() throws Exception {
        WebSocketSession first = session("feature-a", "192.168.1.31");
        WebSocketSession second = session("feature-b", "192.168.1.32");
        registry.register(first, objectMapper.readTree("{\"type\":\"HELLO\"}"));
        registry.register(second, objectMapper.readTree("{\"type\":\"HELLO\"}"));

        registry.broadcastChildMode(true);

        ArgumentCaptor<TextMessage> firstMessage = ArgumentCaptor.forClass(TextMessage.class);
        verify(first).sendMessage(firstMessage.capture());
        assertThat(firstMessage.getValue().getPayload()).contains(
                "CHILD_MODE_CHANGED", "\"childMode\":true");
        verify(second).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesGlobalGameTimeAcrossStartPauseAndResumeAnchors() throws Exception {
        WebSocketSession session = session("session-timing", "192.168.1.28");
        registry.register(session, objectMapper.readTree("{\"type\":\"HELLO\"}"));
        registry.accept(session, objectMapper.readTree("""
                {"type":"GAME_STARTED","eventId":"timing-1","sequence":1,"state":{"engineState":"RUNNING","gameId":7,"gameTime":{"mode":"LIMITED","remainingMillis":60000,"running":true}}}
                """));
        String startedAt = String.valueOf(registry.find("192.168.1.28").get("lastEventAt"));
        Thread.sleep(2L);

        registry.accept(session, objectMapper.readTree("""
                {"type":"GAME_TIMING_CHANGED","eventId":"timing-2","sequence":2,"state":{"engineState":"SETTLING","gameId":7,"gameTime":{"mode":"LIMITED","remainingMillis":57000,"running":false}}}
                """));

        Map<String, Object> paused = registry.find("192.168.1.28");
        assertThat(paused).containsEntry("lastEventType", "GAME_TIMING_CHANGED");
        assertThat(paused.get("lastEventAt")).isNotEqualTo(startedAt);
        Map<String, Object> state = (Map<String, Object>) paused.get("state");
        assertThat((Map<String, Object>) state.get("gameTime"))
                .containsEntry("mode", "LIMITED")
                .containsEntry("remainingMillis", 57_000)
                .containsEntry("running", false);

        registry.accept(session, objectMapper.readTree("""
                {"type":"ROOM_SNAPSHOT","eventId":"timing-3","sequence":3,"state":{"engineState":"RUNNING","gameId":7,"gameTime":{"mode":"LIMITED","remainingMillis":55000,"running":true}}}
                """));
        Map<String, Object> resumedState = (Map<String, Object>) registry.find("192.168.1.28").get("state");
        assertThat((Map<String, Object>) resumedState.get("gameTime"))
                .containsEntry("remainingMillis", 55_000)
                .containsEntry("running", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectsLivePlayersWithStableTiedRanksAndClearsThemWhenIdle() throws Exception {
        WebSocketSession session = session("session-players", "192.168.1.35");
        registry.register(session, objectMapper.readTree("{\"type\":\"HELLO\"}"));
        registry.accept(session, objectMapper.readTree("""
                {"type":"ROOM_SNAPSHOT","sequence":1,"state":{
                  "engineState":"RUNNING",
                  "gameplay":{"players":[{"totalScore":20},{"totalScore":20},{"totalScore":8}]},
                  "playerAccesses":[
                    {"member":{"id":11,"name":"甲"},"access":{"uid":"2283055601"}},
                    {"member":{"id":12,"name":"乙"},"access":{"uid":"2283055602"}},
                    {"member":{"id":13,"name":"丙"},"access":{"uid":"2283055603"}}
                  ]
                }}
                """));

        Map<String, Object> projection = registry.find("192.168.1.35");
        List<Map<String, Object>> players = (List<Map<String, Object>>) projection.get("players");
        assertThat(players).hasSize(3);
        assertThat(players.get(0)).containsEntry("name", "甲").containsEntry("score", 20L).containsEntry("rank", 1);
        assertThat(players.get(1)).containsEntry("name", "乙").containsEntry("score", 20L).containsEntry("rank", 1);
        assertThat(players.get(2)).containsEntry("name", "丙").containsEntry("score", 8L).containsEntry("rank", 3);

        registry.accept(session, objectMapper.readTree("""
                {"type":"GAME_ENDED","sequence":2,"state":{"engineState":"IDLE","gameplay":{"players":[]},"playerAccesses":[]}}
                """));
        assertThat((List<Map<String, Object>>) registry.find("192.168.1.35").get("players")).isEmpty();
    }

    private static WebSocketSession session(String id, String ip) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 12345));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
