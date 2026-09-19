package com.example.backend.security;

import com.example.backend.exception.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Đếm số lần login sai theo IP trong Redis (INCR + EXPIRE) thay vì ConcurrentHashMap trong 1 JVM -
// nhất quán khi chạy nhiều instance app cùng lúc (xem docs/architecture-roadmap.md §5.1). Key:
// rate-limit:login:{ip}. EXPIRE chỉ đặt lần INCR đầu tiên (count vừa lên 1) để giữ đúng semantics
// "fixed window kể từ lần sai đầu tiên" của bản in-memory cũ - INCR lặp lại không làm window trôi.
@Component
@ConditionalOnProperty(name = "auth.login.rate-limiter", havingValue = "redis", matchIfMissing = true)
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final String KEY_PREFIX = "rate-limit:login:";

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxAttempts;
    private final Duration window;

    public RedisLoginRateLimiter(StringRedisTemplate redisTemplate,
                                  @Value("${auth.login.max-attempts}") int maxAttempts,
                                  @Value("${auth.login.window-minutes}") long windowMinutes) {
        this.redisTemplate = redisTemplate;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    @Override
    public void checkAllowed(String ip) {
        try {
            String value = redisTemplate.opsForValue().get(key(ip));
            if (value != null && Integer.parseInt(value) >= maxAttempts) {
                throw new TooManyAttemptsException("Too many login attempts. Try again later.");
            }
        } catch (DataAccessException e) {
            // Fail-open có chủ đích: rate limiter là lớp phòng thủ bổ sung (defense-in-depth), không
            // phải control chính (đó là BCrypt + thông báo lỗi giống nhau chống user-enumeration ở
            // AuthService). Redis gián đoạn không nên kéo sập toàn bộ đăng nhập của hệ thống.
            log.warn("Redis unavailable, skipping login rate limit check for ip={}", ip, e);
        }
    }

    @Override
    public void recordFailure(String ip) {
        try {
            String redisKey = key(ip);
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, window);
            }
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, could not record login failure for ip={}", ip, e);
        }
    }

    @Override
    public void recordSuccess(String ip) {
        try {
            redisTemplate.delete(key(ip));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, could not reset login attempts for ip={}", ip, e);
        }
    }

    private static String key(String ip) {
        return KEY_PREFIX + ip;
    }
}
