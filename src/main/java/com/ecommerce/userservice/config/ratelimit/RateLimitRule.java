package com.ecommerce.userservice.config.ratelimit;

import lombok.Getter;

@Getter
public enum RateLimitRule {

    LOGIN_EMAIL("login:email"),
    LOGIN_IP("login:ip"),
    REGISTER_IP("register:ip"),
    FORGOT_EMAIL("forgot:email"),
    FORGOT_IP("forgot:ip"),
    RESET_IP("reset:ip"),
    VALIDATE_IP("validate:ip");

    private final String keyPrefix;

    RateLimitRule(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }


}