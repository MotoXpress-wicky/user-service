package com.ecommerce.userservice.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RefreshCookieService {

    @Value("${refresh.cookie.name}")
    private String cookieName;

    @Value("${refresh.cookie.path}")
    private String cookiePath;

    @Value("${refresh.cookie.ttl-minutes}")
    private long cookieTtlMinutes;

    @Value("${refresh.cookie.secure}")
    private boolean secure;

    @Value("${refresh.cookie.same-site}")
    private String sameSite;

    public String getCookieName() {
        return cookieName;
    }

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken)
                .maxAge(Duration.ofMinutes(cookieTtlMinutes))
                .build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();

        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(cookieName) && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(cookiePath);
    }
}
