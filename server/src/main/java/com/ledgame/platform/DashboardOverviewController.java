package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class DashboardOverviewController {
    private final DashboardOverviewService service;
    private final OperatorAuthorizationService authorization;

    public DashboardOverviewController(DashboardOverviewService service,
            OperatorAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        return service.overview();
    }
}
