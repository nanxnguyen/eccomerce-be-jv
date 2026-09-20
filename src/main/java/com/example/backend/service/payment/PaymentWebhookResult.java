package com.example.backend.service.payment;

public record PaymentWebhookResult(
        Long orderId, // Đơn hàng cần cập nhật.
        boolean success, // Kết quả thanh toán do cổng trả về.
        String gatewayTransactionRef // Mã giao dịch từ cổng thanh toán.
) {}
