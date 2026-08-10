package com.ledgame.platform;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "本机服务请求失败" : exception.getReason();
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        if (exception instanceof PlatformApiException platformException) {
            body.put("code", platformException.getCode());
        }
        body.put("message", message);
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }
}
