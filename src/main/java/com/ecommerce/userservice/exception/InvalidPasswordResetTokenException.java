package com.ecommerce.userservice.exception;

public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException() {
        // One identical message for missing / used / expired. Never tell the
        // caller WHICH it was — that distinction is free intel for an attacker.
        super("This password reset link is invalid or has expired.");
    }
}
