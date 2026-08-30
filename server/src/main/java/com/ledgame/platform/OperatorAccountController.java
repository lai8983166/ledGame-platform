package com.ledgame.platform;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class OperatorAccountController {
    private final OperatorAccountService accounts;

    public OperatorAccountController(OperatorAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/operator-auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return accounts.login(request.username(), request.password());
    }

    @GetMapping("/operator-accounts")
    public List<Map<String, Object>> list() {
        return accounts.listAccounts();
    }

    @PostMapping("/operator-accounts")
    public Map<String, Object> create(
            @RequestBody CreateAccountRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.createOperator(
                request.username(), request.displayName(), request.password(), operatorId);
    }

    @PutMapping("/operator-accounts/{id}")
    public Map<String, Object> update(
            @PathVariable Long id,
            @RequestBody UpdateAccountRequest request) {
        return accounts.updateProfile(id, request.username(), request.displayName());
    }

    @PutMapping("/operator-accounts/{id}/password")
    public Map<String, Object> resetPassword(
            @PathVariable Long id,
            @RequestBody PasswordRequest request) {
        return accounts.resetPassword(id, request.password());
    }

    @PutMapping("/operator-accounts/{id}/enabled")
    public Map<String, Object> setEnabled(
            @PathVariable Long id,
            @RequestBody EnabledRequest request) {
        return accounts.setEnabled(id, request.enabled());
    }

    public record LoginRequest(String username, String password) {}
    public record CreateAccountRequest(String username, String displayName, String password) {}
    public record UpdateAccountRequest(String username, String displayName) {}
    public record PasswordRequest(String password) {}
    public record EnabledRequest(Boolean enabled) {}
}
