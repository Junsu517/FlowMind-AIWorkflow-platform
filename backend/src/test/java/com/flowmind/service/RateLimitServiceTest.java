package com.flowmind.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Redis 上的 Lua 限流验证：同 key 连续获取，第 11 次被拒。 */
@SpringBootTest
class RateLimitServiceTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void fixedWindow_allowsTenRejectsEleventh() {
        String key = "rl:test:" + UUID.randomUUID();
        try {
            for (int i = 1; i <= 10; i++) {
                assertTrue(rateLimitService.tryAcquire(key, 10, 60),
                        "第 " + i + " 次应放行");
            }
            assertFalse(rateLimitService.tryAcquire(key, 10, 60), "第 11 次应被限流");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void differentKeysIndependent() {
        String key1 = "rl:test:" + UUID.randomUUID();
        String key2 = "rl:test:" + UUID.randomUUID();
        try {
            for (int i = 0; i < 10; i++) {
                assertTrue(rateLimitService.tryAcquire(key1, 10, 60));
            }
            assertFalse(rateLimitService.tryAcquire(key1, 10, 60));
            assertTrue(rateLimitService.tryAcquire(key2, 10, 60), "不同 key 互不影响");
        } finally {
            redisTemplate.delete(key1);
            redisTemplate.delete(key2);
        }
    }
}
