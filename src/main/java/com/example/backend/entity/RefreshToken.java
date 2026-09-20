package com.example.backend.entity;

import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.FetchType; // annotation/API JPA để ánh xạ entity với database (FetchType).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.JoinColumn; // annotation/API JPA để ánh xạ entity với database (JoinColumn).
import jakarta.persistence.ManyToOne; // annotation/API JPA để ánh xạ entity với database (ManyToOne).
import jakarta.persistence.Table; // annotation/API JPA để ánh xạ entity với database (Table).
import lombok.AllArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (AllArgsConstructor).
import lombok.Builder; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Builder).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

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
