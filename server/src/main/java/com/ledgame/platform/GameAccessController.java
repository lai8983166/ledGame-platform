package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game-access")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class GameAccessController {
    private final GameAccessService accessService;

    public GameAccessController(GameAccessService accessService) {
        this.accessService = accessService;
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody ActivateRequest request) {
        return accessService.activate(request.uid());
    }

    public record ActivateRequest(String uid, String deviceId, String roomId) {}
}
