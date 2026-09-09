package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/player-info")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class PlayerInfoController {
    private final PlayerInfoService playerInfoService;

    public PlayerInfoController(PlayerInfoService playerInfoService) {
        this.playerInfoService = playerInfoService;
    }

    @GetMapping
    public Map<String, Object> find(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String wristbandUid) {
        return playerInfoService.find(phone, wristbandUid);
    }
}
