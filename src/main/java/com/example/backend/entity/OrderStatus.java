package com.example.backend.entity;

public enum OrderStatus {
    PENDING_PAYMENT, // Đơn đang chờ thanh toán.
    CONFIRMED, // Đơn đã xác nhận.
    SHIPPED, // Đơn đang được giao.
    DELIVERED, // Đơn đã giao cho khách.
    CANCELLED // Đơn đã bị hủy.
}
