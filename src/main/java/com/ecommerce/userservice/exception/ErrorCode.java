package com.ecommerce.userservice.exception;

import lombok.Getter;


@Getter
public enum ErrorCode {

    // ---------- token problems (HTTP 401) ----------
    ACCESS_TOKEN_MISSING("No access token was provided"),
    ACCESS_TOKEN_EXPIRED("Access token has expired", true),
    ACCESS_TOKEN_INVALID("Access token is malformed or has an invalid signature"),

    REFRESH_TOKEN_MISSING("No refresh token was provided"),
    REFRESH_TOKEN_EXPIRED("Refresh token has expired"),
    REFRESH_TOKEN_INVALID("Refresh token is not recognised"),
    REFRESH_TOKEN_REUSED("Refresh token was already used - all sessions revoked"),

    UNAUTHENTICATED("Authentication is required to access this resource"),

    // ---------- sign-in problems ----------
    INVALID_CREDENTIALS("Invalid email or password."),
    ACCOUNT_LOCKED("This account has been locked. Please contact support if you believe this is a mistake."),
    ACCESS_DENIED("You do not have permission to access this resource."),

    // ---------- bad input (HTTP 400) ----------
    VALIDATION_FAILED("Please check the submitted data."),
    INVALID_REQUEST("The request could not be understood."),
    INVALID_CAPTCHA("The security check failed. Please try again."),
    INVALID_RESET_TOKEN("This password reset link is invalid or has expired."),

    // ---------- conflicts (HTTP 409) ----------
    EMAIL_ALREADY_EXISTS("An account with this email already exists."),
    OAUTH_ACCOUNT_EXISTS("This email is registered with another sign-in method."),
    DATA_CONFLICT("This request conflicts with existing data."),

    // ---------- other ----------
    RATE_LIMITED("Too many requests. Please try again later."),
    INTERNAL_ERROR("Something went wrong. Please try again later.");

    /** Name of the request attribute the JWT filter uses to pass a code to the entry point. */
    public static final String REQUEST_ATTRIBUTE = "auth.error.code";

    private final String message;
    private final boolean refreshable;

    ErrorCode(String message) {
        this(message, false);
    }

    ErrorCode(String message, boolean refreshable) {
        this.message = message;
        this.refreshable = refreshable;
    }


}
