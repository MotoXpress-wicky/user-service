package com.ecommerce.userservice.exception;

import com.ecommerce.userservice.entity.AuthProvider;

public class OAuthAccountException extends RuntimeException {
    private final AuthProvider provider;

    public OAuthAccountException(AuthProvider provider) {
        super("Account registered with " + provider);
        this.provider = provider;
    }

    public AuthProvider getProvider() {
        return provider;
    }
}