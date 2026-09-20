package com.example.backend.dto;

import jakarta.validation.constraints.Min; // annotation/API kiểm tra dữ liệu đầu vào (Min).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

// JSON gửi khi đổi số lượng của một dòng trong giỏ.
public record CartItemQuantityRequest(
        @NotNull @Min(1) Integer quantity // Chặn null và số lượng 0/âm ngay ở ranh giới API.
) {}
