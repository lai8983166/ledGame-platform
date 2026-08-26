package com.ledgame.platform;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class DashboardOverviewController {
    private final DashboardOverviewService service;

    public DashboardOverviewController(DashboardOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }
}
