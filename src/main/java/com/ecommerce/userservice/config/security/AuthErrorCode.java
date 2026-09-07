package com.ecommerce.userservice.config.security;

public enum AuthErrorCode {

    ACCESS_TOKEN_MISSING("No access token was provided", false),
    ACCESS_TOKEN_EXPIRED("Access token has expired", true),
    ACCESS_TOKEN_INVALID("Access token is malformed or has an invalid signature", false),

    REFRESH_TOKEN_MISSING("No refresh token was provided", false),
    REFRESH_TOKEN_EXPIRED("Refresh token has expired", false),
    REFRESH_TOKEN_INVALID("Refresh token is not recognised", false),
    REFRESH_TOKEN_REUSED("Refresh token was already used - all sessions revoked", false),
    ACCOUNT_LOCKED("This account has been locked", false),
    UNAUTHENTICATED("Authentication is required to access this resource", false);


    public static final String REQUEST_ATTRIBUTE = "auth.error.code";

    private final String message;

    private final boolean refreshable;

    AuthErrorCode(String message, boolean refreshable) {
        this.message = message;
        this.refreshable = refreshable;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRefreshable() {
        return refreshable;
    }
}