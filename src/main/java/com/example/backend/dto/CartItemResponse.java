package com.example.backend.dto;

import com.example.backend.entity.CartItem;
import com.example.backend.entity.ProductVariant;

import java.math.BigDecimal;

// price/subtotal đọc LIVE từ ProductVariant (không snapshot) - khác OrderItemResponse. Xem spec §4.
public record CartItemResponse(Long id, Long variantId, String productName, String sku, BigDecimal price, Integer quantity, BigDecimal subtotal) {
    public static CartItemResponse from(CartItem item) {
        ProductVariant variant = item.getVariant();
        BigDecimal subtotal = variant.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), variant.getId(), variant.getProduct().getName(), variant.getSku(),
                variant.getPrice(), item.getQuantity(), subtotal);
    }
}
