package com.example.backend.scheduler;

import com.example.backend.service.AuthService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// @EnableScheduling đã bật ở BackendApplication. fixedRate=1h: bảng refresh_tokens lớn dần chậm
// (1 row/lần rotate), không cần quét thường xuyên như OrderExpiryScheduler.
@Component
public class RefreshTokenCleanupScheduler {

    private final AuthService authService;

    public RefreshTokenCleanupScheduler(AuthService authService) {
        this.authService = authService;
    }

    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void cleanupExpiredRefreshTokens() {
        authService.cleanupExpiredRefreshTokens();
    }
}
