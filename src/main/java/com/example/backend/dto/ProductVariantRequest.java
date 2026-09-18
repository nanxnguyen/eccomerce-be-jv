package com.example.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// DTO yêu cầu tạo/cập nhật biến thể sản phẩm (sku, kích cỡ, màu, giá, tồn kho)
public record ProductVariantRequest(
        @NotBlank String sku,
        String size,
        String color,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity
) {}
