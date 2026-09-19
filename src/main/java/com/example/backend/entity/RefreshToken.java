package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Một phiên refresh token (opaque, không phải JWT) - xem docs/architecture-roadmap.md §4.3.
 * Chỉ lưu tokenHash (SHA-256 của raw token), không bao giờ lưu raw token.
 * revokedAt != null nghĩa là token đã bị rotate/logout - KHÔNG xóa row khi rotate, vì cần giữ lại
 * để phát hiện reuse (client cũ gửi lại token đã rotate = dấu hiệu token bị đánh cắp).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    // Không đổi qua cả chuỗi rotate - logout/logout-all/reuse-detection revoke theo sessionId
    // để tắt toàn bộ chuỗi token kế thừa nhau, không chỉ token hiện tại.
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
