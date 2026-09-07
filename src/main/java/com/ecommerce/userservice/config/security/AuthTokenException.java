package com.ecommerce.userservice.config.security;

import org.springframework.security.core.AuthenticationException;

public class AuthTokenException extends AuthenticationException {

    private final AuthErrorCode errorCode;

    public AuthTokenException(AuthErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AuthErrorCode getErrorCode() {
        return errorCode;
    }
}
