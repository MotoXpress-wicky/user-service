package com.ecommerce.userservice.exception;

public class InvalidCaptchaException extends RuntimeException {
    public InvalidCaptchaException(String message) {
        super(message);
    }
}