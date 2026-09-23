-- 固定窗口限流：INCR 后首个请求设置过期窗口，保证原子性
-- KEYS[1] = 限流 key（如 rl:execute:{userId}）
-- ARGV[1] = 窗口秒数
-- 返回当前窗口内计数
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
