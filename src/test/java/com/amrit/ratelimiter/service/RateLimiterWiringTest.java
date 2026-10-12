package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterWiringTest {
    @Configuration @Import({RedisConfig.class, RateLimiterService.class})
    static class Config {
        @Bean RedisConnectionFactory factory() { return mock(RedisConnectionFactory.class); }
        @Bean StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) { return new StringRedisTemplate(factory); }
    }
    @Test void resolvesNamedTemplatesWhenDefaultTemplateAlsoExists() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(context.getBean(RateLimiterService.class)).isNotNull();
        }
    }
}
