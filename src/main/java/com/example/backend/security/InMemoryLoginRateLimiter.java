package com.example.backend.security;

import com.example.backend.exception.TooManyAttemptsException; // TooManyAttemptsException (loại lỗi nghiệp vụ hoặc dữ liệu).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (ConditionalOnProperty).
import org.springframework.scheduling.annotation.Scheduled; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Scheduled).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).

import java.time.Duration; // kiểu/thao tác thời gian chuẩn Java (Duration).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.concurrent.ConcurrentHashMap; // tiện ích collection chuẩn Java (ConcurrentHashMap).
import java.util.concurrent.atomic.AtomicInteger; // tiện ích collection chuẩn Java (AtomicInteger).

// Chỉ dùng cho test (auth.login.rate-limiter=in-memory trong src/test/resources/application.properties)
// - test không cần Redis thật để chạy. Production dùng RedisLoginRateLimiter (đếm nhất quán across
// nhiều instance, xem docs/architecture-roadmap.md §5.1).
@Component
@ConditionalOnProperty(name = "auth.login.rate-limiter", havingValue = "in-memory")
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Attempt> attemptsByIp = new ConcurrentHashMap<>();

    public InMemoryLoginRateLimiter(@Value("${auth.login.max-attempts}") int maxAttempts,
                                     @Value("${auth.login.window-minutes}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    @Override
    public void checkAllowed(String ip) {
        Attempt attempt = attemptsByIp.get(ip);
        if (attempt != null && !attempt.isExpired(window) && attempt.count.get() >= maxAttempts) {
            throw new TooManyAttemptsException("Too many login attempts. Try again later.");
        }
    }

    @Override
    public void recordFailure(String ip) {
        attemptsByIp.compute(ip, (key, existing) -> {
            if (existing == null || existing.isExpired(window)) {
                return new Attempt();
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    @Override
    public void recordSuccess(String ip) {
        attemptsByIp.remove(ip);
    }

    // Dọn entry hết hạn định kỳ - không có bước này thì map phình vô hạn theo số IP distinct đã
    // từng login sai ít nhất 1 lần, kể cả IP không quay lại nữa.
    @Scheduled(fixedRate = 5 * 60 * 1000)
    void cleanupExpired() {
        attemptsByIp.entrySet().removeIf(entry -> entry.getValue().isExpired(window));
    }

    private static final class Attempt {
        private final AtomicInteger count = new AtomicInteger(1);
        private final Instant windowStart = Instant.now();

        boolean isExpired(Duration window) {
            return Instant.now().isAfter(windowStart.plus(window));
        }
    }
}
