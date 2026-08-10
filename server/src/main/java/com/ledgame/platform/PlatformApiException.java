package com.ledgame.platform;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public class PlatformApiException extends ResponseStatusException {
    private final String code;

    public PlatformApiException(HttpStatusCode status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
