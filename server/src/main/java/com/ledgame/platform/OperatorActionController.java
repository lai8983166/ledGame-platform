package com.ledgame.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operator-actions")
public class OperatorActionController {
    @PostMapping("/system-settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordSystemSettingsChange() {
        // The interceptor records the already-successful desktop setting change.
    }
}
