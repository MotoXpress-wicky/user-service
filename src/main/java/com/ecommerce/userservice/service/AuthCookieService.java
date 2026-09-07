package com.ecommerce.userservice.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class AuthCookieService {

    @Value("${auth.cookie.name}")
    private String cookieName;

    @Value("${auth.cookie.path}")
    private String cookiePath;

    @Value("${auth.cookie.ttl-minutes}")
    private long cookieTtlMinutes;

    @Value("${auth.cookie.secure}")
    private boolean secure;

    @Value("${auth.cookie.same-site}")
    private String sameSite;


    public ResponseCookie create(String accessToken) {
        return base(accessToken)
                .maxAge(Duration.ofMinutes(cookieTtlMinutes))
                .build();
    }

    /**
     * Path must match create() exactly or the browser ignores the deletion.
     */
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

    /**
     * ResponseCookie.maxAge(long) is in SECONDS - always pass a Duration instead.
     */
    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(cookiePath);
    }
}