package com.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Dịch vụ JWT (JSON Web Token) - cung cấp access token được signed để xác thực người dùng.
 * JWT là một chuỗi được mã hóa chứa thông tin (userId, vai trò, thời hạn) và được ký bằng khóa bí mật.
 * Client gửi token này trong header của mỗi request, server xác thực token bằng cùng khóa bí mật.
 */
@Component
public class JwtService {

    // Khóa bí mật dùng để ký JWT - chỉ server biết, dùng để xác minh token không bị giả mạo
    private final SecretKey key;
    // Thời gian token hết hạn (tính bằng millisecond)
    private final long expirationMs;

    // @Value: đọc giá trị từ application.properties (jwt.secret, jwt.expiration-ms)
    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long expirationMs) {
        // Chuyển string secret thành SecretKey dùng cho HMAC-SHA256
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Tạo JWT access token mới chứa userId (subject) và vai trò, ký bằng khóa bí mật.
     * sub = userId thay vì email vì email có thể đổi; jti (random UUID) để token có định danh
     * riêng, dùng cho việc blacklist theo từng token sau này (Phase 2, auth:blacklist:{jti}).
     */
    public String generateToken(Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // Lấy userId từ token (userId được lưu dưới dạng subject)
    public Long extractUserId(String token) {
        return Long.valueOf(extractAllClaims(token).getSubject());
    }

    // Lấy vai trò từ token
    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    // Lấy jti (định danh riêng của token)
    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    /**
     * Kiểm tra token có hợp lệ không: chữ ký đúng và chưa hết hạn.
     * Catch JwtException: nếu token bị giả mạo, hết hạn, hay lỗi gì, trả về false thay vì throw
     * exception (cách an toàn cho caller - không cần try-catch mỗi lần gọi).
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (JwtException e) {
            return false;
        }
    }

    // Giải mã token, xác minh chữ ký, và trích dữ liệu bên trong
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)          // Xác minh chữ ký bằng khóa bí mật
                .build()
                .parseSignedClaims(token) // Giải mã token
                .getPayload();            // Trả về dữ liệu bên trong
    }
}
