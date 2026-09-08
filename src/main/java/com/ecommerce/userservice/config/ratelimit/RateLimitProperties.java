package com.ecommerce.userservice.config.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Binds the rate-limit block of application.yaml.
 * <p>
 * Spring's relaxed binding maps the yaml key "login-email" onto the enum
 * constant LOGIN_EMAIL, so the map keys stay readable in configuration while
 * staying type-safe in code. A typo in the yaml becomes a startup failure
 * rather than a silently missing limit.
 */
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /**
     * Master switch. Set false in src/test/resources/application.yaml so the
     * test suite never needs Redis running.
     */
    private boolean enabled = true;

    /**
     * Whether to believe the X-Forwarded-For header when working out the
     * client IP.
     * <p>
     * Keep this FALSE unless your app genuinely sits behind a proxy you
     * control. Anyone can put whatever they like in that header, so trusting
     * it on a directly-exposed server means an attacker sends a different fake
     * IP with every request and every request gets a fresh bucket. Your rate
     * limiting would be completely bypassed and the logs would look normal.
     * <p>
     * Turn it on when you deploy behind Azure Application Gateway or nginx.
     */
    private boolean trustProxyHeaders = false;

    /** Largest number of distinct keys held in memory for LOCAL-scope rules. */
    private int localCacheMaxSize = 100_000;

    /** How long an unused LOCAL bucket is kept before eviction. */
    private Duration localCacheExpiry = Duration.ofMinutes(30);

    private Map<RateLimitRule, Limit> rules = new EnumMap<>(RateLimitRule.class);

    /**
     * One bucket's shape.
     *
     * @param capacity how many requests are allowed in a burst, and also the
     *                 number of tokens added back over one full period
     * @param period   how long a completely empty bucket takes to refill
     */
    public record Limit(long capacity, Duration period) {
        public Limit {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("period must be positive");
            }
        }
    }

    /**
     * Fails fast at startup rather than at 3am when someone finally tries the
     * endpoint. A missing rule is a configuration bug, not a reason to silently
     * stop protecting an endpoint.
     */
    public Limit require(RateLimitRule rule) {
        Limit limit = rules.get(rule);
        if (limit == null) {
            throw new IllegalStateException(
                    "No rate limit configured for " + rule + ". Add rate-limit.rules."
                            + rule.name().toLowerCase().replace('_', '-') + " to application.yaml");
        }
        return limit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustProxyHeaders() {
        return trustProxyHeaders;
    }

    public void setTrustProxyHeaders(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public int getLocalCacheMaxSize() {
        return localCacheMaxSize;
    }

    public void setLocalCacheMaxSize(int localCacheMaxSize) {
        this.localCacheMaxSize = localCacheMaxSize;
    }

    public Duration getLocalCacheExpiry() {
        return localCacheExpiry;
    }

    public void setLocalCacheExpiry(Duration localCacheExpiry) {
        this.localCacheExpiry = localCacheExpiry;
    }

    public Map<RateLimitRule, Limit> getRules() {
        return rules;
    }

    public void setRules(Map<RateLimitRule, Limit> rules) {
        this.rules = rules;
    }
}
