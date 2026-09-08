package com.ecommerce.userservice.exception;

/**
 * Thrown when a bucket is empty.
 * <p>
 * The message is deliberately generic and identical for every rule. Saying
 * "too many attempts for this email address" would confirm that the address
 * has an account - the exact leak PasswordResetService works so hard to avoid
 * with its uniform 200 response. A rate limit message is an easy place to give
 * that away by accident.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final String GENERIC_MESSAGE = "Too many requests. Please try again later.";

    /** Seconds the caller should wait, sent back in the Retry-After header. */
    private final long retryAfterSeconds;

    /**
     * True when the limiter rejected because its store was unreachable rather
     * than because the caller genuinely ran out of tokens. Only used for
     * logging - the response looks identical either way, because telling a
     * caller "our Redis is down" is information they do not need.
     */
    private final boolean causedByStoreFailure;

    public RateLimitExceededException(long retryAfterSeconds) {
        this(retryAfterSeconds, false);
    }

    public RateLimitExceededException(long retryAfterSeconds, boolean causedByStoreFailure) {
        super(GENERIC_MESSAGE);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.causedByStoreFailure = causedByStoreFailure;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isCausedByStoreFailure() {
        return causedByStoreFailure;
    }
}
