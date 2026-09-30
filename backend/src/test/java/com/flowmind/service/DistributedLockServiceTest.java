package com.flowmind.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis 分布式锁：互斥、value 校验释放、TTL 过期（真实 Redis）。 */
@SpringBootTest
class DistributedLockServiceTest {

    @Autowired
    private DistributedLockService lockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void mutualExclusion() throws Exception {
        String key = "lock:test:" + UUID.randomUUID();
        try {
            assertTrue(lockService.acquire(key, "holder-1", 60));
            assertFalse(lockService.acquire(key, "holder-2", 60), "同 key 二次获取应失败");
            assertTrue(redisTemplate.opsForValue().get(key).equals("holder-1"));

            // 释放别人的锁（value 不匹配）不动锁
            lockService.release(key, "holder-2");
            assertTrue(redisTemplate.hasKey(key), "value 不匹配时不得删除");
            assertFalse(lockService.acquire(key, "holder-2", 60));

            // 正确 value 释放后可重获
            lockService.release(key, "holder-1");
            assertFalse(redisTemplate.hasKey(key));
            assertTrue(lockService.acquire(key, "holder-2", 60));
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void concurrentAcquireOnlyOneWins() throws Exception {
        String key = "lock:test:" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try {
            for (int i = 0; i < 8; i++) {
                String holder = "h" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    if (lockService.acquire(key, holder, 60)) {
                        wins.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, wins.get(), "8 并发只有一个拿到锁");
        } finally {
            pool.shutdownNow();
            redisTemplate.delete(key);
        }
    }

    @Test
    void ttlExpiryAllowsReacquire() {
        String key = "lock:test:" + UUID.randomUUID();
        try {
            assertTrue(lockService.acquire(key, "holder-1", 1));
            await().atMost(java.time.Duration.ofSeconds(3))
                    .until(() -> lockService.acquire(key, "holder-2", 60));
        } finally {
            redisTemplate.delete(key);
        }
    }
}
