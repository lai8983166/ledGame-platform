package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game-plays")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class GamePlayController {
    private final GamePlayService gamePlayService;

    public GamePlayController(GamePlayService gamePlayService) {
        this.gamePlayService = gamePlayService;
    }

    @GetMapping
    public java.util.List<Map<String, Object>> list() {
        return gamePlayService.list();
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody GamePlayService.StartCommand command) {
        return gamePlayService.start(command);
    }

    @PutMapping("/{id}/result")
    public Map<String, Object> settle(
            @PathVariable long id,
            @RequestBody GamePlayService.ResultCommand command) {
        return gamePlayService.settle(id, command);
    }
}
