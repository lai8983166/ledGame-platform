package com.ledgame.platform;

import java.util.Map;
import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/game-plays")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class GamePlayController {
    private final GamePlayService gamePlayService;
    private final OperatorAuthorizationService authorization;

    public GamePlayController(GamePlayService gamePlayService, OperatorAuthorizationService authorization) {
        this.gamePlayService = gamePlayService;
        this.authorization = authorization;
    }

    @GetMapping
    public java.util.List<Map<String, Object>> list(@RequestParam(required = false) Long memberId,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        return gamePlayService.list(memberId);
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody GamePlayService.StartCommand command) {
        return gamePlayService.start(command);
    }

    @PostMapping("/start-batch")
    public List<Map<String, Object>> startBatch(
            @RequestBody GamePlayService.BatchStartCommand command) {
        return gamePlayService.startBatch(command);
    }

    @PutMapping("/{id}/result")
    public Map<String, Object> settle(
            @PathVariable long id,
            @RequestBody GamePlayService.ResultCommand command) {
        return gamePlayService.settle(id, command);
    }
}
