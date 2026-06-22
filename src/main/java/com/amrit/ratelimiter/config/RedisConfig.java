package com.amrit.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * Removes stale entries, checks the limit, records an allowed request, and
     * refreshes the key TTL in one Redis command. The returned value is
     * "allowed:requestCount:retryAfterSeconds".
     */
    @Bean(name = "slidingWindowRateLimitScript")
    public DefaultRedisScript<String> slidingWindowRateLimitScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setResultType(String.class);
        script.setScriptText("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowStart = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local ttlSeconds = tonumber(ARGV[4])
            local member = ARGV[5]
            local windowMs = tonumber(ARGV[6])

            redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
            local count = redis.call('ZCARD', key)

            if count >= limit then
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local retryAfter = math.max(1, math.ceil((tonumber(oldest[2]) + windowMs - now) / 1000))
                return '0:' .. count .. ':' .. retryAfter
            end

            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, ttlSeconds)
            return '1:' .. (count + 1) .. ':0'
            """);
        return script;
    }

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
