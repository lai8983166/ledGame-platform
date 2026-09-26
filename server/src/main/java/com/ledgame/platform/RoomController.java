package com.ledgame.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin(origins = "*")
public class RoomController {
    private final RoomConnectionRegistry registry;
    private final LegacyRoomPresenceService legacyRooms;
    private final RoomSettingsService settings;
    private final OperatorAuthorizationService authorization;

    public RoomController(RoomConnectionRegistry registry, LegacyRoomPresenceService legacyRooms, RoomSettingsService settings,
            OperatorAuthorizationService authorization) {
        this.registry = registry;
        this.legacyRooms = legacyRooms;
        this.settings = settings;
        this.authorization = authorization;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        java.util.LinkedHashMap<String, Map<String, Object>> projections = new java.util.LinkedHashMap<>();
        registry.list().forEach(room -> projections.put(String.valueOf(room.get("ip")), room));
        legacyRooms.list().forEach(room -> projections.putIfAbsent(String.valueOf(room.get("ip")), room));
        return settings.merge(List.copyOf(projections.values()));
    }

    @GetMapping("/{ip}")
    public Map<String, Object> get(@PathVariable String ip,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        Map<String, Object> projection = registry.find(ip);
        if (projection == null) projection = legacyRooms.find(ip);
        Map<String, Object> room = projection == null
                ? settings.merge(List.of()).stream()
                        .filter(item -> item.get("ip").equals(RoomConnectionRegistry.normalizeIp(ip)))
                        .findFirst().orElse(null)
                : settings.merge(List.of(projection)).get(0);
        if (room == null) throw new RoomNotFoundException();
        return room;
    }

    @PutMapping("/{ip}")
    public Map<String, Object> rename(@PathVariable String ip, @RequestBody RoomNameRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        settings.saveName(ip, request == null ? null : request.roomName());
        return get(ip, operatorId);
    }

    public record RoomNameRequest(String roomName) {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class RoomNotFoundException extends RuntimeException {}
}
