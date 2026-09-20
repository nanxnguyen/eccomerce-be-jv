package com.example.backend.service.payment;

// Lỗi bất ngờ khi gọi API của gateway thật (Stripe/VNPay) lúc initiate() - không tự bắt riêng, rơi
// vào handler Exception.class chung của GlobalExceptionHandler (500) như mọi lỗi hạ tầng khác.
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause); // Giữ nguyên lỗi gốc để tiện tìm nguyên nhân khi xem log.
    }
}
