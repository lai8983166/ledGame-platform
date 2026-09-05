package com.ledgame.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feature-settings")
@CrossOrigin(origins = "*")
public class ChildModeController {
    private final ChildModeService childMode;
    private final RoomConnectionRegistry rooms;

    public ChildModeController(ChildModeService childMode, RoomConnectionRegistry rooms) {
        this.childMode = childMode;
        this.rooms = rooms;
    }

    @GetMapping
    public Map<String, Boolean> get() {
        return Map.of("childMode", childMode.enabled());
    }

    @PutMapping("/child-mode")
    public Map<String, Boolean> update(@RequestBody ChildModeRequest request) {
        boolean enabled = childMode.update(request != null && request.enabled());
        rooms.broadcastChildMode(enabled);
        return Map.of("childMode", enabled);
    }

    public record ChildModeRequest(boolean enabled) {}
}
