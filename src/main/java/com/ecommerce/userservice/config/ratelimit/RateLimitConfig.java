package com.ecommerce.userservice.config.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ===================================================================
 * THIS IS THE ONLY FILE THAT TOUCHES THE BUCKET4J REDIS API.
 * ===================================================================
 * <p>
 * Bucket4j renamed its Maven artifacts and reshaped its builders in 8.11, so
 * tutorials written before that will not compile against 8.19. Everything
 * else in this feature talks to the plain {@link ProxyManager} interface, so
 * if a signature below is wrong, this is the only file you need to correct.
 * Check https://bucket4j.com/ for the version you actually pulled.
 * <p>
 * The whole class is switched off when rate-limit.enabled is false, which is
 * how the test suite avoids needing a running Redis.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Azure Cache for Redis refuses unencrypted connections, so this must be
     * true in production. Locally the container has no certificate, so false.
     */
    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean redisSsl;

    /**
     * A dedicated Lettuce client for rate limiting.
     * <p>
     * Note this is separate from the RedisConnectionFactory that
     * spring-boot-starter-data-redis creates. Bucket4j needs a raw Lettuce
     * connection with a specific codec, which is not what Spring's factory
     * hands out. Both still speak to the same Redis server, and both are still
     * Lettuce, so there is no second client library in the build.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withSsl(redisSsl)
                // If Redis is unreachable, fail in 2 seconds rather than
                // hanging a Tomcat thread. The FAIL_OPEN / FAIL_CLOSED policy
                // on each rule then decides what happens to the request.
                .withTimeout(Duration.ofSeconds(2));

        if (redisPassword != null && !redisPassword.isBlank()) {
            uri.withPassword(redisPassword.toCharArray());
        }

        log.info("Rate limiting will use Redis at {}:{} (ssl={})", redisHost, redisPort, redisSsl);
        return RedisClient.create(uri.build());
    }

    /**
     * Keys are Strings so they are readable in redis-cli. Values are raw bytes
     * because that is how Bucket4j serialises bucket state.
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    /**
     * The bridge between Bucket4j's algorithm and Redis storage.
     * <p>
     * The expiration strategy is what stops Redis filling up forever. Once a
     * bucket has sat full for a while it is deleted, because a full bucket is
     * indistinguishable from one that never existed. Without this, every IP
     * that ever visited you would keep a key alive permanently.
     */
    @Bean
    public ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
                .build();
    }
}
