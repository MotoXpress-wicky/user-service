package com.ecommerce.userservice.config.security;

import com.ecommerce.userservice.exception.ErrorCode;
import org.springframework.security.core.AuthenticationException;

/**
 * Thrown when an access token or refresh token is missing, expired or wrong.
 * GlobalExceptionHandler turns it into the shared ErrorResponse.
 */
public class AuthTokenException extends AuthenticationException {

    private final ErrorCode errorCode;

    public AuthTokenException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
