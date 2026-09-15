package com.ledgame.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class RoomConnectionRegistry {
    private final RoomConnectionProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Connection> byIp = new ConcurrentHashMap<>();
    private final Map<String, Connection> bySession = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> roomProjections = new ConcurrentHashMap<>();

    public RoomConnectionRegistry(RoomConnectionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Connection register(WebSocketSession session, JsonNode hello) {
        if (!properties.isEnabled()) throw protocolError("ROOM_CONNECTION_DISABLED", "Room connection is disabled");
        String ip = sourceIp(session);
        Connection connection = new Connection(
                session,
                ip,
                UUID.randomUUID().toString(),
                text(hello, "deviceId"),
                text(hello, "roomId"),
                text(hello, "roomName"));
        Connection previous = byIp.put(ip, connection);
        if (previous != null) {
            bySession.remove(previous.session().getId());
            close(previous.session(), CloseStatus.POLICY_VIOLATION);
        }
        bySession.put(session.getId(), connection);
        updateProjection(connection, true);
        return connection;
    }

    public synchronized EventResult accept(WebSocketSession session, JsonNode message) {
        Connection connection = bySession.get(session.getId());
        if (connection == null) throw protocolError("ROOM_CONNECTION_NOT_REGISTERED", "Send HELLO first");
        String type = text(message, "type");
        if (!RoomConnectionProtocol.EVENT_TYPES.contains(type)) {
            throw protocolError("ROOM_EVENT_UNSUPPORTED", "Unsupported room event type");
        }
        long sequence = message.path("sequence").asLong(-1);
        if (sequence < 0) throw protocolError("ROOM_EVENT_SEQUENCE_REQUIRED", "Event sequence is required");
        String eventId = text(message, "eventId");
        if (sequence <= connection.lastSequence()) {
            return new EventResult(connection, true, eventId, sequence);
        }
        connection.lastSequence(sequence);
        connection.lastEventType(type);
        connection.lastEventAt(Instant.now().toString());
        JsonNode state = message.get("state");
        if (state != null && state.isObject()) {
            connection.state(objectMapper.convertValue(state, Map.class));
        }
        connection.queueLength(readQueueLength(state));
        updateProjection(connection, true);
        return new EventResult(connection, false, eventId, sequence);
    }

    public void unregister(WebSocketSession session) {
        Connection connection = bySession.remove(session.getId());
        if (connection != null) {
            byIp.remove(connection.ip(), connection);
            Map<String, Object> projection = roomProjections.get(connection.ip());
            if (projection != null) {
                projection.put("online", false);
                projection.put("players", List.of());
            }
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        roomProjections.values().stream()
                .sorted(java.util.Comparator.comparing(item -> String.valueOf(item.get("ip"))))
                .forEach(item -> result.add(new LinkedHashMap<>(item)));
        return result;
    }

    public Map<String, Object> find(String ip) {
        Map<String, Object> projection = roomProjections.get(normalizeIp(ip));
        return projection == null ? null : new LinkedHashMap<>(projection);
    }

    public boolean hasActiveBusiness() {
        return roomProjections.values().stream().anyMatch(projection -> {
            Object queueLength = projection.get("queueLength");
            if (queueLength instanceof Number number && number.intValue() > 0) return true;
            Object rawState = projection.get("state");
            if (!(rawState instanceof Map<?, ?> state)) return false;
            Object engineStateValue = state.get("engineState");
            String engineState = engineStateValue == null ? "" : String.valueOf(engineStateValue);
            return "RUNNING".equalsIgnoreCase(engineState)
                    || "PREPARING".equalsIgnoreCase(engineState);
        });
    }

    public void broadcastChildMode(boolean childMode) {
        Map<String, Object> payload = Map.of(
                "type", RoomConnectionProtocol.CHILD_MODE_CHANGED,
                "childMode", childMode);
        for (Connection connection : List.copyOf(bySession.values())) {
            try {
                synchronized (connection.session()) {
                    if (connection.session().isOpen()) {
                        connection.session().sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                    }
                }
            } catch (Exception error) {
                unregister(connection.session());
            }
        }
    }

    private static String sourceIp(WebSocketSession session) {
        InetSocketAddress address = session.getRemoteAddress();
        if (address == null) return "unknown";
        return normalizeIp(address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress());
    }

    static String normalizeIp(String value) {
        String ip = value == null ? "" : value.trim();
        return "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) ? "127.0.0.1" : ip;
    }

    private static int readQueueLength(JsonNode state) {
        if (state == null) return 0;
        JsonNode queue = state.get("queueSummary");
        return queue != null && queue.get("waiting") != null && queue.get("waiting").isArray()
                ? queue.get("waiting").size() : 0;
    }

    private void updateProjection(Connection connection, boolean online) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ip", connection.ip());
        item.put("deviceId", connection.deviceId());
        item.put("roomId", connection.roomId());
        item.put("roomName", connection.roomName());
        item.put("connectionId", connection.connectionId());
        item.put("online", online && connection.session().isOpen());
        item.put("state", connection.state());
        item.put("lastSequence", connection.lastSequence());
        item.put("lastEventType", connection.lastEventType());
        item.put("lastEventAt", connection.lastEventAt());
        item.put("queueLength", connection.queueLength());
        item.put("players", livePlayers(connection.state()));
        roomProjections.put(connection.ip(), item);
    }

    private List<Map<String, Object>> livePlayers(Map<String, Object> state) {
        if (state == null) return List.of();
        String engineState = String.valueOf(state.getOrDefault("engineState", "IDLE")).toUpperCase();
        if (!(engineState.equals("RUNNING") || engineState.equals("STARTING")
                || engineState.equals("PREPARING") || engineState.equals("SETTLING"))) return List.of();
        Map<String, Object> gameplay = state.get("gameplay") instanceof Map<?, ?> raw
                ? toStringMap(raw) : Map.of();
        List<?> gameplayPlayers = gameplay.get("players") instanceof List<?> list ? list : List.of();
        List<?> accesses = state.get("playerAccesses") instanceof List<?> list ? list : List.of();
        // A score array without member/access identities is not enough to claim
        // that a real member is playing. Keep the room card honest for older
        // clients that have not started reporting playerAccesses yet.
        int count = accesses.size();
        if (count == 0) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> gamePlayer = index < gameplayPlayers.size() && gameplayPlayers.get(index) instanceof Map<?, ?> raw
                    ? toStringMap(raw) : Map.of();
            Map<String, Object> access = index < accesses.size() && accesses.get(index) instanceof Map<?, ?> raw
                    ? toStringMap(raw) : Map.of();
            Map<String, Object> member = access.get("member") instanceof Map<?, ?> raw
                    ? toStringMap(raw) : Map.of();
            Map<String, Object> accessInfo = access.get("access") instanceof Map<?, ?> raw
                    ? toStringMap(raw) : Map.of();
            LinkedHashMap<String, Object> player = new LinkedHashMap<>();
            player.put("id", member.getOrDefault("id", accessInfo.getOrDefault("uid", "player-" + (index + 1))));
            player.put("memberId", member.get("id"));
            player.put("name", text(member.get("name"), text(member.get("phone"), "玩家 " + (index + 1))));
            player.put("wristbandUid", accessInfo.get("uid"));
            player.put("playerIndex", index);
            long currentScore = score(gamePlayer);
            if (gamePlayer.isEmpty() && count == 1) {
                currentScore = number(gameplay.get("memberPoints"));
            }
            player.put("score", currentScore);
            player.put("participating", true);
            result.add(player);
        }
        List<Map<String, Object>> ranking = result.stream()
                .sorted(java.util.Comparator.comparingLong((Map<String, Object> item) -> number(item.get("score"))).reversed()
                        .thenComparingInt(item -> (int) number(item.get("playerIndex"))))
                .toList();
        long previous = Long.MIN_VALUE;
        int rank = 0;
        for (int index = 0; index < ranking.size(); index++) {
            Map<String, Object> player = ranking.get(index);
            long points = number(player.get("score"));
            if (points != previous) { rank = index + 1; previous = points; }
            player.put("rank", rank);
        }
        return result;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static long score(Map<String, Object> player) {
        for (String key : List.of("memberPoints", "totalScore", "score", "stageScore")) {
            if (player.get(key) instanceof Number number) return Math.max(0, number.longValue());
        }
        return 0;
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }

    private static String text(Object value, String fallback) {
        String valueText = value == null ? "" : String.valueOf(value).trim();
        return valueText.isBlank() ? fallback : valueText;
    }

    private static String text(JsonNode node, String name) {
        String value = node == null ? "" : node.path(name).asText("").trim();
        return value;
    }

    private static IllegalArgumentException protocolError(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception ignored) {
            // The connection is already being fenced; no further action is needed.
        }
    }

    public record EventResult(Connection connection, boolean duplicate, String eventId, long sequence) {}

    public static final class Connection {
        private final WebSocketSession session;
        private final String ip;
        private final String connectionId;
        private final String deviceId;
        private final String roomId;
        private final String roomName;
        private volatile long lastSequence = -1;
        private volatile String lastEventType;
        private volatile String lastEventAt;
        private volatile int queueLength;
        private volatile Map<String, Object> state = Map.of();

        private Connection(WebSocketSession session, String ip, String connectionId, String deviceId, String roomId, String roomName) {
            this.session = session;
            this.ip = ip;
            this.connectionId = connectionId;
            this.deviceId = deviceId;
            this.roomId = roomId;
            this.roomName = roomName;
        }

        public WebSocketSession session() { return session; }
        public String ip() { return ip; }
        public String connectionId() { return connectionId; }
        public String deviceId() { return deviceId; }
        public String roomId() { return roomId; }
        public String roomName() { return roomName; }
        public long lastSequence() { return lastSequence; }
        public void lastSequence(long value) { lastSequence = value; }
        public String lastEventType() { return lastEventType; }
        public void lastEventType(String value) { lastEventType = value; }
        public String lastEventAt() { return lastEventAt; }
        public void lastEventAt(String value) { lastEventAt = value; }
        public int queueLength() { return queueLength; }
        public void queueLength(int value) { queueLength = value; }
        public Map<String, Object> state() { return state; }
        public void state(Map<String, Object> value) { state = value == null ? Map.of() : value; }
    }
}
