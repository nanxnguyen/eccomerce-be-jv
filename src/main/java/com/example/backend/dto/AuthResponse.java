package com.example.backend.dto;

/**
 * DTO trả về sau khi đăng ký/đăng nhập/refresh thành công.
 * accessToken: JWT ngắn hạn dùng cho mỗi request (Authorization: Bearer ...).
 * refreshToken: chuỗi ngẫu nhiên opaque, dài hạn, chỉ dùng để gọi /api/auth/refresh lấy access
 * token mới - xem docs/architecture-roadmap.md §4.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        long refreshExpiresIn,
        UserResponse user
) {}
