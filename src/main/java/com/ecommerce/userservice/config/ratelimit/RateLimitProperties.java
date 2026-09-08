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

    /**
     * This section populate with propertise from application.yaml. Because this class is annotated with
     *
     * @ConfigurationProperties(prefix = "rate-limit")
     * (prefix = "rate-limit") here prefix is the prefix of the property in application.yaml. i.e. rate-limit.enabled
     *
     **/
    private boolean enabled = true;
    private boolean failOpen = false;
    private boolean trustProxyHeaders = false;
    private Redis redis = new Redis(); // Mirroring the Redis class below
    private Map<RateLimitRule, Limit> rules = new HashMap<>();


    /**
     * What static means here
     * <p>
     * Without static, a nested class secretly holds a reference to the outer object. You could not create one on its own:
     * Redis r = new Redis();                        // compile error
     * Redis r = outerObject.new Redis();            // required — awkward
     * With static, it is an independent class that just happens to live inside another file:
     * Redis r = new Redis();                        // works
     **/
    @Getter
    @Setter
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private boolean ssl = false;
        private Duration timeout = Duration.ofSeconds(2);
    }

    /**
     * A Data-structure to hold the limit for a given rule.
     * capacity: the number of requests allowed in the given period
     * period: the time period in which the limit applies
     * <p>
     * record data-structure has implicit constructor. Also we can check the validity of the data before setting
     * value to attributes.
     *
     **/
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
     * Helper method to get the limit for a give rule.
     *
     **/
    public Limit require(RateLimitRule rule) {
        Limit limit = rules.get(rule);
        if (limit == null) {
            throw new IllegalStateException("No limit configured. Add rate-limit.rules."
                    + rule.name().toLowerCase().replace('_', '-') + " to application.yaml");
        }
        return limit;
    }
}