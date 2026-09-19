package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body dùng chung cho POST /api/auth/refresh và POST /api/auth/logout - cả 2 đều xác định
 * phiên đăng nhập bằng refresh token, không phải access token.
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {}
