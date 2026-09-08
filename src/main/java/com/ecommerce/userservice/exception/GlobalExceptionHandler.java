package com.ecommerce.userservice.exception;

import com.ecommerce.userservice.config.security.AuthErrorCode;
import com.ecommerce.userservice.config.security.AuthErrorResponse;
import com.ecommerce.userservice.config.security.AuthTokenException;
import com.ecommerce.userservice.service.AuthCookieService;
import com.ecommerce.userservice.service.RefreshCookieService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AuthCookieService authCookieService;
    private final RefreshCookieService refreshCookieService;

    public GlobalExceptionHandler(AuthCookieService authCookieService, RefreshCookieService refreshCookieService) {
        this.authCookieService = authCookieService;
        this.refreshCookieService = refreshCookieService;
    }


    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        if (exception.isCausedByStoreFailure()) {
            log.error("Rejected {} because Redis was unreachable", request.getRequestURI());
        }

        ErrorResponse response = buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                exception.getMessage(),
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(response);
    }

    @ExceptionHandler(OAuthAccountException.class)
    public ResponseEntity<ErrorResponse> handleOAuthAccount(
            OAuthAccountException exception,
            HttpServletRequest request
    ) {
        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT,
                "This email is registered with " + exception.getProvider() + ". Please use that to sign in.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        log.warn("Validation error occurred", exception);

        List<String> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> formatFieldError(fieldError))
                .toList();

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Please check the submitted data.",
                request.getRequestURI(),
                details
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException exception,
            HttpServletRequest request
    ) {
        log.warn("Bad credentials", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }


    @ExceptionHandler(AuthTokenException.class)
    public ResponseEntity<AuthErrorResponse> handleAuthTokenException(AuthTokenException exception, HttpServletRequest request) {


        AuthErrorCode code = exception.getErrorCode();
        log.warn("Auth token error occurred {}", code, exception);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNAUTHORIZED);

        if (code == AuthErrorCode.REFRESH_TOKEN_EXPIRED
                || code == AuthErrorCode.REFRESH_TOKEN_INVALID
                || code == AuthErrorCode.REFRESH_TOKEN_REUSED) {
            builder.header(HttpHeaders.SET_COOKIE, authCookieService.clear().toString());
            builder.header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString());

        }

        return builder.body(AuthErrorResponse.create(code));

    }


    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ErrorResponse> handleLockedException(
            LockedException exception,
            HttpServletRequest request
    ) {
        log.warn("Locked account login attempt", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "This account has been locked. Please contact support if you believe this is a mistake.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }


    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        log.warn("Authentication error occurred", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        log.warn("Access denied", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error("Database constraint violation", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT,
                "This request conflicts with existing data.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        log.warn("Invalid request parameter type", exception);

        String message = "Invalid value for parameter: " + exception.getName();

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                message,
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        log.warn("Illegal argument exception occurred", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(UserAlreadyExistsException exception, HttpServletRequest request) {

        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(
            InvalidPasswordResetTokenException exception,
            HttpServletRequest request
    ) {
        log.warn("Invalid password reset token used");

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unexpected error occurred", exception);

        ErrorResponse response = buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                exception.getMessage() != null ? exception.getMessage() : "Something went wrong. Please try again later.",
                request.getRequestURI(),
                List.of()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }


    private ErrorResponse buildErrorResponse(
            HttpStatus status,
            String message,
            String path,
            List<String> details
    ) {
        return new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                details
        );
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
