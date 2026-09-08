package com.ecommerce.userservice.exception;

import lombok.Getter;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private static final String GENERIC_MESSAGE = "Too many requests. Please try again later.";

    private final long retryAfterSeconds;
    private final boolean causedByStoreFailure;

    public RateLimitExceededException(long retryAfterSeconds) {
        this(retryAfterSeconds, false);
    }

    public RateLimitExceededException(long retryAfterSeconds, boolean causedByStoreFailure) {
        super(GENERIC_MESSAGE);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.causedByStoreFailure = causedByStoreFailure;
    }


}