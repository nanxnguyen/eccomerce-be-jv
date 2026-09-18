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

/**
 * Dịch vụ JWT (JSON Web Token) - cung cấp token được signed để xác thực người dùng.
 * JWT là một chuỗi được mã hóa chứa thông tin (email, vai trò, thời hạn) và được ký bằng khóa bí mật.
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
     * Tạo JWT token mới chứa email và vai trò, ký bằng khóa bí mật.
     * Token này sẽ gửi cho client và client gửi lại trong mỗi request để chứng minh danh tính.
     */
    public String generateToken(String email, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)           // Chủ thể token là email người dùng
                .claim("role", role)      // Thêm thông tin vai trò vào token
                .issuedAt(now)            // Thời điểm phát hành
                .expiration(expiry)       // Thời điểm token hết hạn
                .signWith(key, Jwts.SIG.HS256)  // Ký token bằng khóa bí mật
                .compact();               // Chuyển thành string
    }

    // Lấy email từ token (email được lưu dưới dạng subject)
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    // Lấy vai trò từ token
    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    /**
     * Kiểm tra token có hợp lệ không:
     * - Email trong token phải khớp expectedEmail
     * - Token chưa hết hạn
     * Catch JwtException: nếu token bị giả mạo hay lỗi gì, trả về false thay vì throw exception
     * (Cách an toàn cho caller - không cần try-catch mỗi lần gọi)
     */
    public boolean isTokenValid(String token, String expectedEmail) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getSubject().equals(expectedEmail) && claims.getExpiration().after(new Date());
        } catch (JwtException e) {
            // Token bị giả mạo, hết hạn, hoặc lỗi ký → không hợp lệ
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
