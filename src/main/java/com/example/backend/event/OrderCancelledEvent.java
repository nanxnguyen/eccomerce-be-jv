package com.example.backend.event;

public record OrderCancelledEvent(
        Long orderId, // ID đơn hàng vừa bị hủy.
        String reason // Lý do hủy để listener gửi thông báo phù hợp.
) {}
