package com.example.backend.dto;

import com.example.backend.entity.OrderItem; // OrderItem (entity ánh xạ dữ liệu với bảng database).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

public record OrderItemResponse(Long id, String productName, String sku, BigDecimal unitPrice, Integer quantity, BigDecimal lineTotal) {
    // Đổi một OrderItem entity thành một object JSON lồng trong OrderResponse.
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                // Giữ lại ID và thông tin snapshot của sản phẩm lúc mua.
                item.getId(), item.getProductName(), item.getSku(), item.getUnitPrice(), item.getQuantity(),
                // Tính thành tiền một dòng: đơn giá nhân số lượng.
                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
