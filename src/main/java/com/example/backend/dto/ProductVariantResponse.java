package com.example.backend.dto;

import com.example.backend.entity.ProductVariant;

import java.math.BigDecimal;

// DTO phản hồi chi tiết biến thể sản phẩm sau khi lưu
public record ProductVariantResponse(Long id, String sku, String size, String color, BigDecimal price, Integer stockQuantity) {
    // Chuyển đổi từ entity ProductVariant sang DTO response
    public static ProductVariantResponse from(ProductVariant variant) {
        return new ProductVariantResponse(variant.getId(), variant.getSku(), variant.getSize(), variant.getColor(), variant.getPrice(), variant.getStockQuantity());
    }
}
