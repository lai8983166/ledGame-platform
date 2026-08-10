package com.ledgame.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin(origins = "*")
public class RoomController {
    private final RoomConnectionRegistry registry;

    public RoomController(RoomConnectionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.list();
    }

    @GetMapping("/{ip}")
    public Map<String, Object> get(@PathVariable String ip) {
        Map<String, Object> room = registry.find(ip);
        if (room == null) throw new RoomNotFoundException();
        return room;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class RoomNotFoundException extends RuntimeException {}
}
