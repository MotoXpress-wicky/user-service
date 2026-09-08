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

    public void check(RateLimitRule rule, String key) {
        if (!properties.isEnabled()) {
            return;
        }

        String bucketKey = buildKey(rule, key);

        try {
            ConsumptionProbe probe = bucket(rule, bucketKey).tryConsumeAndReturnRemaining(1);

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

    public void refund(RateLimitRule rule, String key) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            bucket(rule, buildKey(rule, key)).addTokens(1);
        } catch (Exception e) {
            log.warn("Could not refund a token for {}", rule, e);
        }
    }

    private Bucket bucket(RateLimitRule rule, String bucketKey) {
        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            throw new IllegalStateException("Rate limiting is enabled but Redis is not configured");
        }

        RateLimitProperties.Limit limit = properties.require(rule);

        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build();

        return proxyManager.builder().build(bucketKey, () -> configuration);
    }

    private String buildKey(RateLimitRule rule, String key) {
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