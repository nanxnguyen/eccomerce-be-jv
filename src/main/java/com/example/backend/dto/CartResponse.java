package com.example.backend.dto;

import com.example.backend.entity.Cart; // Cart (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.CartItem; // CartItem (entity ánh xạ dữ liệu với bảng database).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.util.List; // danh sách phần tử cùng kiểu.

public record CartResponse(Long id, List<CartItemResponse> items, BigDecimal totalAmount) {
    // Gom entity giỏ hàng và các dòng hàng thành cấu trúc JSON cho client.
    public static CartResponse from(Cart cart, List<CartItem> items) {
        // Chuyển từng dòng entity thành DTO để tránh trả entity/lộ quan hệ database.
        List<CartItemResponse> itemResponses = items.stream().map(CartItemResponse::from).toList();
        // Cộng subtotal của tất cả dòng; BigDecimal giữ phép tính tiền dạng thập phân chính xác.
        BigDecimal total = itemResponses.stream().map(CartItemResponse::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Trả ID giỏ, các dòng đã chuyển đổi và tổng tiền.
        return new CartResponse(cart.getId(), itemResponses, total);
    }
}
