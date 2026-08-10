package com.ledgame.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class RoomConnectionWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final RoomConnectionRegistry registry;

    public RoomConnectionWebSocketHandler(ObjectMapper objectMapper, RoomConnectionRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            String type = payload.path("type").asText("");
            if (RoomConnectionProtocol.HELLO.equals(type)) {
                RoomConnectionRegistry.Connection connection = registry.register(session, payload);
                send(session, Map.of(
                        "type", RoomConnectionProtocol.WELCOME,
                        "connectionId", connection.connectionId(),
                        "ip", connection.ip(),
                        "epoch", connection.connectionId()));
                return;
            }
            RoomConnectionRegistry.EventResult result = registry.accept(session, payload);
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", RoomConnectionProtocol.ACK);
            ack.put("eventId", result.eventId());
            ack.put("sequence", result.sequence());
            ack.put("duplicate", result.duplicate());
            send(session, ack);
        } catch (Exception exception) {
            sendError(session, exception.getMessage() == null ? "ROOM_PROTOCOL_ERROR" : exception.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    private void send(WebSocketSession session, Object value) throws Exception {
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(value)));
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            send(session, Map.of("type", RoomConnectionProtocol.ERROR, "message", message));
        } catch (Exception ignored) {
            // The socket may already be closed.
        }
    }
}
