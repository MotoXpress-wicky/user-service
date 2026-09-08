package com.ecommerce.userservice.config.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private boolean failOpen = false;
    private boolean trustProxyHeaders = false;
    private Redis redis = new Redis();
    private Map<RateLimitRule, Limit> rules = new HashMap<>();

    @Getter
    @Setter
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private boolean ssl = false;
        private Duration timeout = Duration.ofSeconds(2);
    }

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

    public Limit require(RateLimitRule rule) {
        Limit limit = rules.get(rule);
        if (limit == null) {
            throw new IllegalStateException("No limit configured. Add rate-limit.rules."
                    + rule.name().toLowerCase().replace('_', '-') + " to application.yaml");
        }
        return limit;
    }
}