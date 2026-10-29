package com.ecommerce.userservice.exception;

/** An admin change that the rules do not allow, for example locking your own account. Answered with 409. */
public class AdminRuleException extends RuntimeException {
    public AdminRuleException(String message) {
        super(message);
    }
}
