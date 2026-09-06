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
    private final ActivationService activation;

    public RoomConnectionWebSocketConfig(ObjectMapper objectMapper, RoomConnectionRegistry registry,
            ChildModeService childMode, ActivationService activation) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.childMode = childMode;
        this.activation = activation;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RoomConnectionWebSocketHandler(objectMapper, this.registry, childMode, activation), "/ws/rooms")
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    @Override public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.http.server.ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler handler,
                            java.util.Map<String, Object> attributes) throws Exception {
                        if (activation.activated()) return true;
                        response.setStatusCode(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                        objectMapper.writeValue(response.getBody(), java.util.Map.of("code", "PLATFORM_NOT_ACTIVATED", "message", "会员管理端尚未激活"));
                        return false;
                    }
                    @Override public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.http.server.ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler handler, Exception exception) {}
                })
                .setAllowedOriginPatterns("*");
    }
}
