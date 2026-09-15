package com.ledgame.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/store-settings")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class StoreSettingsController {
    private final StoreSettingsService service;
    private final OperatorAuthorizationService authorization;

    public StoreSettingsController(StoreSettingsService service, OperatorAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping
    public Map<String, Object> get() { return service.get(); }

    @PatchMapping
    public Map<String, Object> update(@RequestBody StoreSettingsService.StoreSettingsPatch patch,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.FEATURE_SETTINGS);
        return service.update(patch == null ? new StoreSettingsService.StoreSettingsPatch(null, null, null, null, null, null) : patch);
    }
}
