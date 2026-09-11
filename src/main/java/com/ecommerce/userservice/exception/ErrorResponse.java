package com.ecommerce.userservice.exception;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * The ONE error body this service sends. Every handler, filter and entry point
 * uses it, so the UI only ever has to understand one shape.
 *
 * @param timestamp   when the error happened
 * @param status      HTTP status number, for example 401
 * @param code        stable key for the UI to branch on, for example ACCESS_TOKEN_EXPIRED
 * @param message     sentence to show the user
 * @param path        the URL that failed
 * @param refreshable true only when the UI may call /refresh and retry once
 * @param details     field errors, for example ["email: Enter a valid email"]; usually empty
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        boolean refreshable,
        List<String> details
) {

    /** Uses the default message that the code carries. */
    public static ErrorResponse of(HttpStatus status, ErrorCode code, String path) {
        return of(status, code, code.getMessage(), path, List.of());
    }

    /** Same, but with a message written by the handler. */
    public static ErrorResponse of(HttpStatus status, ErrorCode code, String message, String path) {
        return of(status, code, message, path, List.of());
    }

    /** Full version. Used when there are field errors to send. */
    public static ErrorResponse of(HttpStatus status,
                                   ErrorCode code,
                                   String message,
                                   String path,
                                   List<String> details) {
        return new ErrorResponse(
                Instant.now(),
                status.value(),
                code.name(),
                message,
                path,
                code.isRefreshable(),
                details
        );
    }
}
