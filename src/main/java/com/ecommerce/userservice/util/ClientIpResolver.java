package com.ecommerce.userservice.util;

import com.ecommerce.userservice.config.ratelimit.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Works out which IP address to count a request against.
 * <p>
 * This looks trivial and is not. Getting it wrong in either direction breaks
 * the rate limiter completely:
 * <p>
 * Trust X-Forwarded-For when you should not, and an attacker simply sends a
 * different fake value with every request. Each fake value gets its own fresh
 * bucket, so the limit never triggers and your logs look perfectly healthy
 * while you are being brute forced.
 * <p>
 * Ignore X-Forwarded-For when you should not, and every request appears to
 * come from your load balancer. All your users share one bucket, and the
 * hundredth visitor of the minute gets a 429 for no reason.
 * <p>
 * Which is correct depends entirely on your deployment, so it is a
 * configuration switch (rate-limit.trust-proxy-headers) rather than a guess.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final RateLimitProperties properties;

    public ClientIpResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustProxyHeaders()) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                /*
                 * The header is a chain: "client, proxy1, proxy2".
                 *
                 * The FIRST entry is the original client. Everything after it
                 * was appended by each proxy in turn. Note that a client can
                 * pre-populate the first entry with a lie - which is exactly
                 * why this whole branch is behind a config flag, and why the
                 * proxy in front of you should be configured to overwrite
                 * rather than append.
                 */
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }

        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
