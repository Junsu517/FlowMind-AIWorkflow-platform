package com.flowmind.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 固定窗口限流（Lua 保证 INCR/EXPIRE 原子性，脚本见 resources/lua/rate_limit.lua）。
 * Redis 故障时放行（fail-open）：限流是保护性措施，不应因 Redis 抖动阻塞主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    /** 返回 false 表示超出限额（当前窗口内计数已大于 limit）。 */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        try {
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT,
                    List.of(key), String.valueOf(windowSeconds));
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("Redis 限流不可用，本次放行: key={}, {}", key, e.getMessage());
            return true;
        }
    }
}
