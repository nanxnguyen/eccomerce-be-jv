package com.example.backend.repository;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).

public interface OrderStatusAggregate {
    OrderStatus getStatus();
    Long getOrderCount();
}
