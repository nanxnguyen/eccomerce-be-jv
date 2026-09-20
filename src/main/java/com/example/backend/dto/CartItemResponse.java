package com.example.backend.dto;

import com.example.backend.entity.CartItem; // CartItem (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.ProductVariant; // ProductVariant (entity ánh xạ dữ liệu với bảng database).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

// price/subtotal đọc LIVE từ ProductVariant (không snapshot) - khác OrderItemResponse. Xem spec §4.
public record CartItemResponse(Long id, Long variantId, String productName, String sku, BigDecimal price, Integer quantity, BigDecimal subtotal) {
    public static CartItemResponse from(CartItem item) {
        ProductVariant variant = item.getVariant();
        BigDecimal subtotal = variant.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), variant.getId(), variant.getProduct().getName(), variant.getSku(),
                variant.getPrice(), item.getQuantity(), subtotal);
    }
}
