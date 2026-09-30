package com.flowmind.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 分布式锁（阶梯 6）：SET NX EX 原子获取，value 存持有者标识，
 * 释放用 Lua 比对 value 防误删他人的锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    /** 获取锁：key=lock:execute:{workflowId}，value=持有者标识（executionId），ttl 秒自动过期兜底。 */
    public boolean acquire(String key, String value, long ttlSeconds) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, java.time.Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 故障 fail-open：放行并记录（锁是防并发优化，主防线仍有 DB 状态检查兜底）
            log.warn("分布式锁获取异常，本次放行: key={}, {}", key, e.getMessage());
            return true;
        }
    }

    /** 释放锁：仅当 value 匹配（自己持有）才删除，Lua 保证原子。 */
    public void release(String key, String value) {
        try {
            Long released = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), value);
            if (released == null || released == 0) {
                log.debug("锁已不属于当前持有者（过期或易主），跳过删除: key={}", key);
            }
        } catch (Exception e) {
            log.warn("分布式锁释放异常（TTL 兜底）: key={}, {}", key, e.getMessage());
        }
    }
}
