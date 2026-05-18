package com.amrit.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * Template for rate limit counters.
     * Key format: "rl:{ip}:{window_timestamp}"
     * Value: integer request count in that window slot
     */
    @Bean(name = "rateLimitTemplate")
    public RedisTemplate<String, String> rateLimitTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Template for the anomaly blocklist.
     * Key format: "block:{ip}"
     * Value: reason string (e.g. "anomaly:burst_pattern")
     * TTL is set per-key to rate-limiter.block-ttl-seconds
     */
    @Bean(name = "blocklistTemplate")
    public RedisTemplate<String, String> blocklistTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
