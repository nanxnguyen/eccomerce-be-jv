package com.example.backend.dto;

import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(Long id, List<CartItemResponse> items, BigDecimal totalAmount) {
    public static CartResponse from(Cart cart, List<CartItem> items) {
        List<CartItemResponse> itemResponses = items.stream().map(CartItemResponse::from).toList();
        BigDecimal total = itemResponses.stream().map(CartItemResponse::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cart.getId(), itemResponses, total);
    }
}
