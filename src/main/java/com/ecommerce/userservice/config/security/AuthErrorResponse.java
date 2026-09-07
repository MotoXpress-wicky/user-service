package com.ecommerce.userservice.config.security;

import java.time.Instant;

public record AuthErrorResponse(
        int status,
        AuthErrorCode code,
        String message,
        boolean refreshable,
        Instant timestamp
) {
    public static AuthErrorResponse create(AuthErrorCode code) {
        return new AuthErrorResponse(401, code, code.getMessage(), code.isRefreshable(), Instant.now());
    }
}