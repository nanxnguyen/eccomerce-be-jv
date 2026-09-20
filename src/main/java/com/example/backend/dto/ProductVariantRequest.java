package com.example.backend.dto;

import jakarta.validation.constraints.DecimalMin; // annotation/API kiểm tra dữ liệu đầu vào (DecimalMin).
import jakarta.validation.constraints.Min; // annotation/API kiểm tra dữ liệu đầu vào (Min).
import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

// DTO yêu cầu tạo/cập nhật biến thể sản phẩm (sku, kích cỡ, màu, giá, tồn kho)
public record ProductVariantRequest(
        @NotBlank String sku,
        String size,
        String color,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity
) {}
