package com.ledgame.platform;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.listAccounts(operatorId);
    }

    @PostMapping("/operator-accounts")
    public Map<String, Object> create(
            @RequestBody CreateAccountRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.createOperator(
                request.username(), request.displayName(), request.password(), request.accountType(), operatorId);
    }

    @PutMapping("/operator-accounts/{id}")
    public Map<String, Object> update(
            @PathVariable Long id,
            @RequestBody UpdateAccountRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.updateProfile(operatorId, id, request.username(), request.displayName());
    }

    @PutMapping("/operator-accounts/{id}/password")
    public Map<String, Object> resetPassword(
            @PathVariable Long id,
            @RequestBody PasswordRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.resetPassword(operatorId, id, request.password());
    }

    @PutMapping("/operator-accounts/{id}/enabled")
    public Map<String, Object> setEnabled(
            @PathVariable Long id,
            @RequestBody EnabledRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.setEnabled(operatorId, id, request.enabled());
    }

    @DeleteMapping("/operator-accounts/{id}")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return accounts.delete(operatorId, id);
    }

    public record LoginRequest(String username, String password) {}
    public record CreateAccountRequest(String username, String displayName, String password, String accountType) {}
    public record UpdateAccountRequest(String username, String displayName) {}
    public record PasswordRequest(String password) {}
    public record EnabledRequest(Boolean enabled) {}
}
