package com.ecommerce.userservice.config.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    private final RateLimitProperties properties;

    public RateLimitConfig(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * create the redis client
     *
     **/
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        RateLimitProperties.Redis redis = properties.getRedis();

        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getPort())
                .withSsl(redis.isSsl())
                .withTimeout(redis.getTimeout()); //How long the application will wait for Redis to respond before giving up.

        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            uri.withPassword(redis.getPassword().toCharArray());
        }

        log.info("Rate limiting using Redis at {}:{} (ssl={})",
                redis.getHost(), redis.getPort(), redis.isSsl());
        return RedisClient.create(uri.build());
    }

    /**
     * Create the connection to redis
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    /**
     * Give proxy server to interact with the buckets inside redis
     *
     **/
    @Bean
    public ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }
}