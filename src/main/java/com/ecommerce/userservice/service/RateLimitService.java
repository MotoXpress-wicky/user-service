package com.ecommerce.userservice.service;

import com.ecommerce.userservice.config.ratelimit.RateLimitProperties;
import com.ecommerce.userservice.config.ratelimit.RateLimitRule;
import com.ecommerce.userservice.exception.RateLimitExceededException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

/**
 * Answers one question: may this request proceed?
 * <p>
 * Everything here is deliberately free of Bucket4j's Redis API - it talks only
 * to the {@link ProxyManager} interface, so swapping Lettuce for Redisson later
 * would not change a line of this file.
 */
@Slf4j
@Service
public class RateLimitService {

    private final RateLimitProperties properties;

    /**
     * ObjectProvider rather than a plain constructor parameter because
     * RateLimitConfig is switched off when rate-limit.enabled is false. This
     * lets the whole application - and the test suite - start with no Redis
     * beans present at all.
     */
    private final ObjectProvider<ProxyManager<String>> proxyManagerProvider;

    /**
     * Buckets for LOCAL-scope rules.
     * <p>
     * Caffeine, not a plain ConcurrentHashMap. This matters: an attacker
     * sending a million unique keys would put a million entries in an unbounded
     * map and eventually take the service down with an OutOfMemoryError - a
     * denial of service carried out through the very code meant to prevent one.
     * A bounded cache evicts instead.
     */
    private final Cache<String, Bucket> localBuckets;

    public RateLimitService(RateLimitProperties properties,
                            ObjectProvider<ProxyManager<String>> proxyManagerProvider) {
        this.properties = properties;
        this.proxyManagerProvider = proxyManagerProvider;
        this.localBuckets = Caffeine.newBuilder()
                .maximumSize(properties.getLocalCacheMaxSize())
                .expireAfterAccess(properties.getLocalCacheExpiry())
                .build();
    }

    /**
     * Takes one token, or throws.
     *
     * @param rule          which limit to apply
     * @param discriminator what to count per - an email address or an IP
     * @throws RateLimitExceededException when the bucket is empty
     */
    public void check(RateLimitRule rule, String discriminator) {
        if (!properties.isEnabled()) {
            return;
        }

        String key = buildKey(rule, discriminator);

        try {
            Bucket bucket = resolveBucket(rule, key);
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                return;
            }

            long retryAfter = Math.max(1,
                    Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());

            /*
             * Log every rejection. On day one you have no idea whether these
             * numbers are right, and this log is the only thing that will tell
             * you. If real users appear here, raise the limit.
             *
             * The key is hashed, so nothing personal is written to the log.
             */
            log.warn("Rate limit hit: rule={} key={} retryAfterSeconds={}", rule, key, retryAfter);

            throw new RateLimitExceededException(retryAfter);

        } catch (RateLimitExceededException e) {
            throw e;

        } catch (Exception e) {
            // The store is unreachable. Which way we fail is a decision made
            // per rule in RateLimitRule, not a side effect of how this
            // exception happened to propagate.
            if (rule.isFailOpen()) {
                log.error("Rate limit store unavailable for {} - ALLOWING request (fail open)", rule, e);
                return;
            }
            log.error("Rate limit store unavailable for {} - REJECTING request (fail closed)", rule, e);
            throw new RateLimitExceededException(5, true);
        }
    }

    /**
     * Puts a token back.
     * <p>
     * Called after a SUCCESSFUL login. Without this, a person who mistypes
     * their password five times and then gets it right is locked out anyway,
     * which is a rate limiter users would rightly complain about. With it, the
     * bucket effectively counts failures rather than attempts, so an ordinary
     * user never notices the limit exists while an attacker - who fails almost
     * every time - still runs out.
     * <p>
     * Never throws. A failed refund is a small inconvenience for one user; it
     * must not turn a successful login into an error.
     */
    public void refund(RateLimitRule rule, String discriminator) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            resolveBucket(rule, buildKey(rule, discriminator)).addTokens(1);
        } catch (Exception e) {
            log.warn("Could not refund a rate limit token for {}", rule, e);
        }
    }

    private Bucket resolveBucket(RateLimitRule rule, String key) {
        if (rule.getScope() == RateLimitRule.Scope.LOCAL) {
            RateLimitProperties.Limit limit = properties.require(rule);
            return localBuckets.get(key, k -> Bucket.builder()
                    .addLimit(bandwidth -> bandwidth
                            .capacity(limit.capacity())
                            .refillGreedy(limit.capacity(), limit.period()))
                    .build());
        }

        BucketConfiguration configuration = configurationFor(rule);

        ProxyManager<String> proxyManager = proxyManagerProvider.getIfAvailable();
        if (proxyManager == null) {
            throw new IllegalStateException(
                    "Rate limiting is enabled but no Redis ProxyManager exists. "
                            + "Check that RateLimitConfig started and Redis is reachable.");
        }

        // build() does not hit Redis. The network call happens on the first
        // tryConsume, inside a Lua script that Redis runs as one atomic step.
        return proxyManager.builder().build(key, () -> configuration);
    }

    private BucketConfiguration configurationFor(RateLimitRule rule) {
        RateLimitProperties.Limit limit = properties.require(rule);
        return BucketConfiguration.builder()
                // refillGreedy spreads the refill smoothly across the period
                // rather than dropping the whole allowance in at once. This is
                // what removes the fixed-window boundary problem, where an
                // attacker fires a full allowance either side of a reset and
                // gets double the limit in one second.
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build();
    }

    /**
     * Builds the Redis key.
     * <p>
     * Two things happen to the discriminator, and both matter.
     * <p>
     * Lowercasing is a security fix, not tidiness. Email addresses are
     * case-insensitive for login, so without this "Nimal@example.com" and
     * "nimal@example.com" would get separate buckets and an attacker would
     * multiply his allowance just by changing capitalisation.
     * <p>
     * Hashing keeps user email addresses out of Redis and out of the logs.
     * Anyone with redis-cli access, and anyone reading a rate limit warning,
     * would otherwise see a list of your users' addresses.
     */
    private String buildKey(RateLimitRule rule, String discriminator) {
        String normalised = discriminator == null
                ? "unknown"
                : discriminator.trim().toLowerCase(Locale.ROOT);
        return "rl:" + rule.getKeyPrefix() + ":" + sha256(normalised);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            // 16 hex chars is 64 bits - far more than enough to avoid
            // collisions here, and it keeps Redis keys short.
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
