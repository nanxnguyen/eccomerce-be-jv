package com.example.backend.dto;

import com.example.backend.entity.Payment; // Payment (entity ánh xạ dữ liệu với bảng database).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

public record PaymentResponse(String gateway, String status, String gatewayTransactionRef, Instant paidAt) {
    // Không trả entity Payment trực tiếp; chuyển enum sang chuỗi dễ dùng trong JSON.
    public static PaymentResponse from(Payment payment) {
        // Lấy thông tin giao dịch đã lưu; paidAt có thể null nếu chưa thanh toán.
        return new PaymentResponse(payment.getGateway().name(), payment.getStatus().name(),
                payment.getGatewayTransactionRef(), payment.getPaidAt());
    }
}
