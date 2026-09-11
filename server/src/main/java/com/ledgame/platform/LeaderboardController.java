package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaderboard")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class LeaderboardController {
    private final LeaderboardService service;
    private final OperatorAuthorizationService authorization;

    public LeaderboardController(LeaderboardService service, OperatorAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping
    public Map<String, Object> getLeaderboard(
            @RequestParam(defaultValue = "day") String period,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        // 自助注册端不携带操作员头，仍可查询玩家排行榜；管理端请求则执行账号权限检查。
        if (operatorId != null) {
            authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        }
        return service.getLeaderboard(period);
    }
}
