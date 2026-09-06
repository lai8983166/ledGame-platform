package com.ledgame.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/activation")
public class ActivationController {
    private final ActivationService activation;
    public ActivationController(ActivationService activation) { this.activation = activation; }
    @GetMapping public ActivationService.Status status(HttpServletRequest request) {
        requireLocal(request); return activation.refresh();
    }
    @PostMapping public ActivationService.Status activate(HttpServletRequest request, @RequestBody CodeRequest body) {
        requireLocal(request); return activation.activate(body.code());
    }
    public record CodeRequest(String code) {
        @Override public String toString() { return "CodeRequest[redacted]"; }
    }
    private void requireLocal(HttpServletRequest request) {
        try { if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return; } catch (Exception ignored) { }
        throw new PlatformApiException(HttpStatus.FORBIDDEN, "ACTIVATION_LOCAL_ONLY", "请在会员管理端本机进行激活");
    }
}
