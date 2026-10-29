package com.ecommerce.userservice.exception;

/** An admin asked for an account that does not exist. Answered with 404. */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("User " + id + " was not found");
    }
}
