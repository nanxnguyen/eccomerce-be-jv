package com.example.backend.security;

import com.example.backend.exception.TooManyAttemptsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Ghim lại contract chung cho cả 2 implementation (in-memory ở đây, Redis - xem RedisLoginRateLimiter):
// dưới ngưỡng thì cho qua, đủ ngưỡng thì chặn, recordSuccess() reset lại. Test chạy trên bản
// in-memory vì đó là bản test dùng thật (auth.login.rate-limiter=in-memory) - không cần Redis thật
// để `mvn test` chạy được.
class InMemoryLoginRateLimiterTest {

    private final InMemoryLoginRateLimiter limiter = new InMemoryLoginRateLimiter(3, 15);

    @Test
    void allowsAttemptsUnderTheLimit() {
        limiter.checkAllowed("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.checkAllowed("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        limiter.checkAllowed("1.2.3.4");
    }

    @Test
    void blocksOnceMaxAttemptsReached() {
        limiter.recordFailure("5.6.7.8");
        limiter.recordFailure("5.6.7.8");
        limiter.recordFailure("5.6.7.8");

        assertThatThrownBy(() -> limiter.checkAllowed("5.6.7.8"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void recordSuccessResetsTheCounter() {
        limiter.recordFailure("9.9.9.9");
        limiter.recordFailure("9.9.9.9");
        limiter.recordFailure("9.9.9.9");

        limiter.recordSuccess("9.9.9.9");

        limiter.checkAllowed("9.9.9.9");
    }

    @Test
    void tracksEachIpIndependently() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");

        assertThatThrownBy(() -> limiter.checkAllowed("1.1.1.1")).isInstanceOf(TooManyAttemptsException.class);
        assertThat(catchNothing(() -> limiter.checkAllowed("2.2.2.2"))).isTrue();
    }

    private boolean catchNothing(Runnable r) {
        r.run();
        return true;
    }
}
