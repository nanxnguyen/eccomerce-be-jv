package com.example.backend.dto;

import com.example.backend.entity.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(Long id, String productName, String sku, BigDecimal unitPrice, Integer quantity, BigDecimal lineTotal) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(), item.getProductName(), item.getSku(), item.getUnitPrice(), item.getQuantity(),
                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
