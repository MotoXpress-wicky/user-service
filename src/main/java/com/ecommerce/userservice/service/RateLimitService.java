package com.ecommerce.userservice.service;

import com.ecommerce.userservice.config.ratelimit.RateLimitProperties;
import com.ecommerce.userservice.config.ratelimit.RateLimitRule;
import com.ecommerce.userservice.exception.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

@Slf4j
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private final ObjectProvider<ProxyManager<String>> proxyManagerProvider;

    public RateLimitService(RateLimitProperties properties,
                            ObjectProvider<ProxyManager<String>> proxyManagerProvider) {
        this.properties = properties;
        this.proxyManagerProvider = proxyManagerProvider;
    }

    /**
     * Here parameter: key means what is use to rate limit.
     * Our case it is email or ip address. It can be anything, user_id,username etc.
     ***/
    public void check(RateLimitRule rule, String key) {
        if (!properties.isEnabled()) {
            return;
        }

        String bucketKey = buildRedisKey(rule, key);

        try {
            /*
             * This is the line that talks to Redis. Split it in two:
             *
             * getBucket(...) gives you a handle. No network yet.
             * .tryConsumeAndReturnRemaining(1) sends the Lua script to Redis. Redis runs it atomically and answers
             * The method name says exactly what it does:
             * - try — attempt it, do not throw if it fails
             * - Consume — take 1 token
             * - AndReturnRemaining — also tell me the state afterwards
             * The answer comes back as a ConsumptionProbe — a small object holding what Redis reported
             * probe.isConsumed()              // true = you got a token
             * probe.getRemainingTokens()      // how many are left
             * probe.getNanosToWaitForRefill() // if refused: how long until one is available
             */
            ConsumptionProbe probe = getBucket(rule, bucketKey).tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                return;
            }

            long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
            log.warn("Rate limit hit: rule={} key={} retryAfterSeconds={}", rule, bucketKey, retryAfter);

            throw new RateLimitExceededException(retryAfter);

        } catch (RateLimitExceededException e) {
            throw e;

        } catch (Exception e) {
            if (properties.isFailOpen()) {
                log.error("Redis unavailable for {} - allowing request (fail open)", rule, e);
                return;
            }
            log.error("Redis unavailable for {} - rejecting request (fail closed)", rule, e);
            throw new RateLimitExceededException(5, true);
        }
    }

    /**
     * Here parameter: key means what is use to rate limit.
     * Our case it is email or ip address. It can be anything, user_id,username etc.
     ***/
    public void refund(RateLimitRule rule, String key) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            getBucket(rule, buildRedisKey(rule, key)).addTokens(1);
        } catch (Exception e) {
            log.warn("Could not refund a token for {}", rule, e);
        }
    }

    /**
     *
     **/
    private Bucket getBucket(RateLimitRule rule, String bucketKey) {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            throw new IllegalStateException("Rate limiting is enabled but Redis is not configured");
        }

        RateLimitProperties.Limit limit = properties.require(rule);


        /**
         *     Describe the bucket's shape to Bucket4j:
         *
         *     capacity(5) — holds 5 tokens maximum
         *     refillGreedy(5, 15m) — adds 5 tokens back over 15 minutes, smoothly
         *
         *     This is only a description. No bucket exists yet, and nothing has touched Redis.
         * **/
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build();


        /**
         * Get the bucket for this key. Two things happen:
         *
         * If Redis already has rl:login:email:c72d..., you get a handle to it
         * If not, Redis creates it with the configuration above
         *
         * Still no network call. The returned object is a handle. Redis is contacted when check() calls tryConsumeAndReturnRemaining(1) on it.
         * **/
        return proxyManager.builder().build(bucketKey, () -> configuration);
    }

    /**
     * helper function use to build the redis key (redis buket key) for a given rule and key.
     * Here parameter: key means IP or Email address.
     * rl: means prefix for all reate limit keys.
     *
     **/
    private String buildRedisKey(RateLimitRule rule, String key) {
        String normalised = key == null ? "unknown" : key.trim().toLowerCase(Locale.ROOT);
        return "rl:" + rule.getKeyPrefix() + ":" + sha256(normalised);
    }


    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}