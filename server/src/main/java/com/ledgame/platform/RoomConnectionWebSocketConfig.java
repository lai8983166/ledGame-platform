package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RoomConnectionWebSocketConfig implements WebSocketConfigurer {
    private final ObjectMapper objectMapper;
    private final RoomConnectionRegistry registry;
    private final ChildModeService childMode;

    public RoomConnectionWebSocketConfig(ObjectMapper objectMapper, RoomConnectionRegistry registry,
            ChildModeService childMode) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.childMode = childMode;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RoomConnectionWebSocketHandler(objectMapper, this.registry, childMode), "/ws/rooms")
                .setAllowedOriginPatterns("*");
    }
}
