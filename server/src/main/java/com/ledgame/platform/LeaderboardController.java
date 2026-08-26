package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaderboard")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class LeaderboardController {
    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> getLeaderboard(@RequestParam(defaultValue = "day") String period) {
        return service.getLeaderboard(period);
    }
}
