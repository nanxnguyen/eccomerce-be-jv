package com.example.backend.event;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).

public record OrderStatusChangedEvent(Long orderId, OrderStatus status) {}
