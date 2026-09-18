package com.example.backend.security;

import com.example.backend.exception.TooManyAttemptsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Chặn brute-force login theo IP: quá auth.login.max-attempts lần sai trong auth.login.window-minutes
// phút thì từ chối, không gọi tới AuthService.login() nữa (tránh tốn công BCrypt.matches() vô ích).
// Key theo IP (KHÔNG theo email): key theo email cho phép 1 kẻ tấn công tự khoá tài khoản người khác
// chỉ bằng cách gửi vài request sai mật khẩu (targeted lockout DoS) - AuthController.checkAllowed()
// gọi TRƯỚC AuthService.findByEmail() nên không mở lại lỗ user-enumeration mà AuthService (dòng 54-65)
// cố tình che.
//
// ponytail: in-memory, 1 instance. Nhiều instance thì mỗi instance đếm riêng (giới hạn hiệu quả
// nhân lên theo số instance) - nếu scale ngang thật, chuyển counter này sang Redis (INCR + EXPIRE).
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Attempt> attemptsByIp = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${auth.login.max-attempts}") int maxAttempts,
                             @Value("${auth.login.window-minutes}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    public void checkAllowed(String ip) {
        Attempt attempt = attemptsByIp.get(ip);
        if (attempt != null && !attempt.isExpired(window) && attempt.count.get() >= maxAttempts) {
            throw new TooManyAttemptsException("Too many login attempts. Try again later.");
        }
    }

    public void recordFailure(String ip) {
        attemptsByIp.compute(ip, (key, existing) -> {
            if (existing == null || existing.isExpired(window)) {
                return new Attempt();
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

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
