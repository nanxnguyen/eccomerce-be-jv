package com.example.backend.dto;

import jakarta.validation.constraints.Min; // annotation/API kiểm tra dữ liệu đầu vào (Min).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

// JSON gửi khi thêm một biến thể sản phẩm vào giỏ.
public record CartItemAddRequest(
        @NotNull Long variantId, // Biến thể cần thêm; không được thiếu.
        @NotNull @Min(1) Integer quantity // Số lượng phải có và ít nhất là 1.
) {}
