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
        String token = text(hello, "token");
        if (properties.getToken().isBlank() || !properties.getToken().equals(token)) {
            throw protocolError("ROOM_CONNECTION_UNAUTHORIZED", "Invalid room connection token");
        }
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
            if (projection != null) projection.put("online", false);
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

    private static String sourceIp(WebSocketSession session) {
        InetSocketAddress address = session.getRemoteAddress();
        if (address == null) return "unknown";
        return normalizeIp(address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress());
    }

    private static String normalizeIp(String value) {
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
        roomProjections.put(connection.ip(), item);
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
