package com.example.backend.event;

public record PaymentConfirmedEvent(Long orderId) {} // Báo cho listener biết thanh toán của đơn đã được xác nhận.
