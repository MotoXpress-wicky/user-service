package com.ecommerce.userservice.exception;

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

import java.util.List;

/**
 * Turns every exception into the same ErrorResponse body.
 *
 * RestControllerAdvice = one class that catches exceptions from all controllers.
 * ExceptionHandler(X.class) = this method handles exception X.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AuthCookieService authCookieService;
    private final RefreshCookieService refreshCookieService;

    public GlobalExceptionHandler(AuthCookieService authCookieService,
                                  RefreshCookieService refreshCookieService) {
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

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(ErrorResponse.of(
                        HttpStatus.TOO_MANY_REQUESTS,
                        ErrorCode.RATE_LIMITED,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(OAuthAccountException.class)
    public ResponseEntity<ErrorResponse> handleOAuthAccount(
            OAuthAccountException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        HttpStatus.CONFLICT,
                        ErrorCode.OAUTH_ACCOUNT_EXISTS,
                        "This email is registered with " + exception.getProvider() + ". Please use that to sign in.",
                        request.getRequestURI()));
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
                .map(this::formatFieldError)
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.getMessage(),
                        request.getRequestURI(),
                        details));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException exception,
            HttpServletRequest request
    ) {
        log.warn("Bad credentials for {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_CREDENTIALS,
                        request.getRequestURI()));
    }

    /**
     * Token problems. The code itself says whether the UI may refresh and retry.
     * A dead refresh token also clears both cookies, so the browser stops sending it.
     */
    @ExceptionHandler(AuthTokenException.class)
    public ResponseEntity<ErrorResponse> handleAuthTokenException(
            AuthTokenException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = exception.getErrorCode();
        log.warn("Auth token error {} on {}", code, request.getRequestURI());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNAUTHORIZED);

        if (code == ErrorCode.REFRESH_TOKEN_EXPIRED
                || code == ErrorCode.REFRESH_TOKEN_INVALID
                || code == ErrorCode.REFRESH_TOKEN_REUSED) {
            builder.header(HttpHeaders.SET_COOKIE, authCookieService.clear().toString());
            builder.header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString());
        }

        return builder.body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, code, request.getRequestURI()));
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ErrorResponse> handleLockedException(
            LockedException exception,
            HttpServletRequest request
    ) {
        log.warn("Locked account login attempt on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.ACCOUNT_LOCKED,
                        request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        log.warn("Authentication error occurred", exception);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCode.UNAUTHENTICATED,
                        request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        log.warn("Access denied on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.ACCESS_DENIED,
                        request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error("Database constraint violation", exception);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        HttpStatus.CONFLICT,
                        ErrorCode.DATA_CONFLICT,
                        request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        log.warn("Invalid request parameter type", exception);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_REQUEST,
                        "Invalid value for parameter: " + exception.getName(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        log.warn("Illegal argument exception occurred", exception);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_REQUEST,
                        request.getRequestURI()));
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(
            UserAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        HttpStatus.CONFLICT,
                        ErrorCode.EMAIL_ALREADY_EXISTS,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(
            InvalidPasswordResetTokenException exception,
            HttpServletRequest request
    ) {
        log.warn("Invalid password reset token used");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_RESET_TOKEN,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(InvalidCaptchaException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCaptcha(
            InvalidCaptchaException exception,
            HttpServletRequest request
    ) {
        log.warn("Captcha check failed for {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_CAPTCHA,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    /**
     * Last resort. Never send exception.getMessage() to the browser: it can hold
     * SQL, file paths or class names. The real cause goes to the log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unexpected error occurred", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCode.INTERNAL_ERROR,
                        request.getRequestURI()));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
