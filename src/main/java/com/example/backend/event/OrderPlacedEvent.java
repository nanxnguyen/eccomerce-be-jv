package com.example.backend.event;

public record OrderPlacedEvent(Long orderId) {} // Báo cho listener biết đơn hàng mới được tạo.
